package com.hmdp.business;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ShopQueryService {
  @Resource private BusinessStore db;
  @Resource private BusinessSchema schema;
  @Resource private StringRedisTemplate redis;
  @Resource private ShopMapper shops;
  private volatile boolean initialized;

  public static void coordinates(Double x, Double y) {
    if (x == null
        || y == null
        || !Double.isFinite(x)
        || !Double.isFinite(y)
        || Math.abs(x) > 180
        || Math.abs(y) > 85.05112878)
      throw new BusinessException(400, "INVALID_COORDINATE", "经纬度不正确");
  }

  public Shop entity(long id) {
    if (schema.ready()
        && db.count("SELECT COUNT(*) FROM tb_shop_settings WHERE shop_id=? AND status<>1", id) > 0)
      return null;
    return shops.selectById(id);
  }

  public Result list(String name, Long type, int page, int size, String sort, Double x, Double y) {
    schema.require();
    BusinessStore.page(page, size);
    if (!Arrays.asList("default", "score", "price", "distance").contains(sort))
      throw new BusinessException(400, "INVALID_SORT", "排序方式不正确");
    List<Object> args = new ArrayList<>();
    String where = " WHERE COALESCE(st.status,1)=1";
    if (type != null) {
      where += " AND s.type_id=?";
      args.add(type);
    }
    if (name != null && !name.trim().isEmpty()) {
      where += " AND s.name LIKE ?";
      args.add("%" + name.trim() + "%");
    }
    if (x != null || y != null) {
      coordinates(x, y);
      Map<Long, Double> dist = new HashMap<>();
      List<Long> types =
          type == null
              ? db.jdbc.queryForList("SELECT id FROM tb_shop_type", Long.class)
              : Collections.singletonList(type);
      for (Long t : types) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> result =
            redis
                .opsForGeo()
                .search(
                    "shop:geo:" + t,
                    GeoReference.fromCoordinate(x, y),
                    new Distance(5, Metrics.KILOMETERS),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(10000));
        if (result != null)
          result.forEach(
              r ->
                  dist.put(
                      Long.parseLong(r.getContent().getName()), r.getDistance().getValue() * 1000));
      }
      if (dist.isEmpty()) return Result.ok(Collections.emptyList(), 0L);
      where += " AND s.id IN (" + String.join(",", Collections.nCopies(dist.size(), "?")) + ")";
      args.addAll(dist.keySet());
      List<Map<String, Object>> rows =
          db.jdbc.queryForList(
              "SELECT s.* FROM tb_shop s LEFT JOIN tb_shop_settings st ON st.shop_id=s.id" + where,
              args.toArray());
      for (Map<String, Object> row : rows)
        row.put("distance", dist.get(BusinessStore.number(row, "id")));
      Comparator<Map<String, Object>> cmp =
          "score".equals(sort)
              ? Comparator.comparingLong(
                      (Map<String, Object> r) -> BusinessStore.number(r, "score"))
                  .reversed()
              : "price".equals(sort)
                  ? Comparator.comparingLong(r -> BusinessStore.number(r, "avg_price"))
                  : Comparator.comparingDouble(
                      r -> Double.parseDouble(r.get("distance").toString()));
      rows.sort(cmp.thenComparingLong(r -> BusinessStore.number(r, "id")));
      int from = Math.min(rows.size(), (page - 1) * size), to = Math.min(rows.size(), from + size);
      return Result.ok(BusinessStore.views(rows.subList(from, to)), (long) rows.size());
    }
    if ("distance".equals(sort)) throw new BusinessException(400, "LOCATION_REQUIRED", "距离排序需要定位");
    String order =
        "score".equals(sort)
            ? "s.score DESC,s.id"
            : "price".equals(sort) ? "s.avg_price,s.id" : "s.id";
    long total =
        db.count(
            "SELECT COUNT(*) FROM tb_shop s LEFT JOIN tb_shop_settings st ON st.shop_id=s.id"
                + where,
            args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    return Result.ok(
        BusinessStore.views(
            db.jdbc.queryForList(
                "SELECT s.* FROM tb_shop s LEFT JOIN tb_shop_settings st ON st.shop_id=s.id"
                    + where
                    + " ORDER BY "
                    + order
                    + " LIMIT ? OFFSET ?",
                args.toArray())),
        total);
  }

  public void syncGeo(long id) {
    Map<String, Object> s =
        db.one(
            "SELECT s.*,COALESCE(st.status,1) status FROM tb_shop s LEFT JOIN tb_shop_settings st ON st.shop_id=s.id WHERE s.id=?",
            id);
    for (Long type : db.jdbc.queryForList("SELECT id FROM tb_shop_type", Long.class))
      redis.opsForGeo().remove("shop:geo:" + type, Long.toString(id));
    if (s != null
        && BusinessStore.number(s, "status") == 1
        && s.get("x") != null
        && s.get("y") != null) {
      double x = Double.parseDouble(s.get("x").toString()),
          y = Double.parseDouble(s.get("y").toString());
      coordinates(x, y);
      redis.opsForGeo().add("shop:geo:" + s.get("type_id"), new Point(x, y), Long.toString(id));
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  public void warm() {
    if (!schema.ready()) return;
    try {
      for (Long id : db.jdbc.queryForList("SELECT id FROM tb_shop", Long.class)) syncGeo(id);
      initialized = true;
    } catch (Exception e) {
      initialized = false;
    }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void retryWarm() {
    if (!initialized) warm();
  }

  public void visit(long shop, HttpServletRequest request) {
    try {
      String who =
          com.hmdp.utils.UserHolder.getUser() == null
              ? request.getRemoteAddr() + ":" + request.getHeader("User-Agent")
              : "u:" + com.hmdp.utils.UserHolder.getUser().getId();
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(who.getBytes(StandardCharsets.UTF_8));
      String value = Base64.getEncoder().encodeToString(hash);
      String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
      String key = "uv:shop:" + shop + ":" + day;
      // HLL 适合小内存近似去重；它只用于访问统计，不能替代精确的订单/核销账目。
      redis.opsForHyperLogLog().add(key, value);
      redis.expire(key, 90, java.util.concurrent.TimeUnit.DAYS);
    } catch (Exception ignored) {
    }
  }
}
