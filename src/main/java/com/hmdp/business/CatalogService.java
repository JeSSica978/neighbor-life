package com.hmdp.business;

import com.hmdp.entity.*;
import com.hmdp.mapper.*;
import com.hmdp.service.ReliableEventService;
import com.hmdp.service.cache.VoucherListCacheService;
import java.time.*;
import java.util.*;
import javax.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  @Resource private BusinessStore db;
  @Resource private BusinessSchema schema;
  @Resource private AccessService access;
  @Resource private ShopMapper shops;
  @Resource private VoucherMapper vouchers;
  @Resource private SeckillVoucherMapper sales;
  @Resource private ReliableEventService events;
  @Resource private ShopQueryService queries;
  @Resource private StringRedisTemplate redis;
  @Resource private VoucherListCacheService cache;
  private static final DefaultRedisScript<Long> INVALIDATE =
      new DefaultRedisScript<>(
          "if redis.call('EXISTS',KEYS[4])==1 then return 0 end;redis.call('INCR',KEYS[3]);redis.call('DEL',KEYS[1],KEYS[2]);redis.call('SET',KEYS[4],'1');return 1",
          Long.class);

  private static long epoch(Object value) {
    if (value == null || value.toString().isEmpty()) return 0;
    try {
      String s = value.toString();
      return s.endsWith("Z")
          ? Instant.parse(s).getEpochSecond()
          : LocalDateTime.parse(s).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    } catch (Exception e) {
      throw new BusinessException(400, "INVALID_TIME", "时间格式不正确");
    }
  }

  public void images(String images, long user, String previous) {
    if (images == null || images.isEmpty() || images.equals(previous)) return;
    for (String path : images.split(","))
      if (!access.admin(user)
          && db.count("SELECT COUNT(*) FROM tb_upload WHERE user_id=? AND path=?", user, path) == 0)
        throw new BusinessException(403, "IMAGE_FORBIDDEN", "只能使用自己上传的图片");
  }

  @Transactional
  public Map<String, Object> saveShop(Shop s) {
    schema.require();
    long user = BusinessStore.user();
    if (s.getId() == null) access.requireAdmin();
    else access.shop(s.getId());
    Shop old = s.getId() == null ? null : shops.selectById(s.getId());
    if (s.getId() != null && old == null)
      throw new BusinessException(404, "SHOP_NOT_FOUND", "店铺不存在");
    if (s.getName() == null && old != null) s.setName(old.getName());
    if (s.getName() == null || s.getName().trim().isEmpty() || s.getName().length() > 128)
      throw new BusinessException(400, "INVALID_SHOP", "店铺名称不正确");
    if (s.getTypeId() == null && old != null) s.setTypeId(old.getTypeId());
    if (s.getTypeId() == null
        || db.count("SELECT COUNT(*) FROM tb_shop_type WHERE id=?", s.getTypeId()) == 0)
      throw new BusinessException(400, "INVALID_TYPE", "店铺分类不存在");
    if (s.getX() == null && old != null) s.setX(old.getX());
    if (s.getY() == null && old != null) s.setY(old.getY());
    ShopQueryService.coordinates(s.getX(), s.getY());
    if (s.getAvgPrice() != null && (s.getAvgPrice() < 0 || s.getAvgPrice() > 1000000))
      throw new BusinessException(400, "INVALID_PRICE", "人均价格不正确");
    images(s.getImages(), user, old == null ? "" : old.getImages());
    s.setSold(null);
    s.setComments(null);
    s.setScore(null);
    s.setCreateTime(null);
    s.setUpdateTime(null);
    if (s.getId() == null) {
      // 这些统计不能由商家输入。教程旧表的 sold/comments/score 没有默认值，
      // 新建时显式从 0 开始；修改店铺时仍忽略客户端传入的统计值。
      s.setSold(0);
      s.setComments(0);
      s.setScore(0);
      if (s.getImages() == null) s.setImages("");
      if (s.getAddress() == null) s.setAddress("");
      shops.insert(s);
      db.jdbc.update("INSERT INTO tb_shop_settings(shop_id,status) VALUES(?,1)", s.getId());
    } else shops.updateById(s);
    String event = events.create("CATALOG_SYNC", s.getId());
    events.afterCommit(event, "CATALOG_SYNC");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", s.getId().toString());
    result.put("eventId", event);
    return result;
  }

  @Transactional
  public void shopStatus(long shop, int status) {
    access.shop(shop);
    if (status != 1 && status != 2) throw new BusinessException(400, "INVALID_STATUS", "店铺状态不正确");
    if (shops.selectById(shop) == null) throw new BusinessException(404, "SHOP_NOT_FOUND", "店铺不存在");
    db.jdbc.update(
        "INSERT INTO tb_shop_settings(shop_id,status) VALUES(?,?) ON DUPLICATE KEY UPDATE status=VALUES(status)",
        shop,
        status);
    String event = events.create("CATALOG_SYNC", shop);
    events.afterCommit(event, "CATALOG_SYNC");
  }

  @Transactional
  public long createVoucher(Map<String, Object> b, int type) {
    schema.require();
    long shop = BusinessStore.positive(b, "shopId");
    access.shop(shop);
    if (shops.selectById(shop) == null) throw new BusinessException(404, "SHOP_NOT_FOUND", "店铺不存在");
    Voucher v = new Voucher();
    v.setShopId(shop);
    v.setTitle(BusinessStore.required(b, "title", 255));
    v.setSubTitle(BusinessStore.string(b, "subTitle"));
    v.setRules(BusinessStore.required(b, "rules", 1024));
    v.setPayValue(BusinessStore.positive(b, "payValue"));
    v.setActualValue(BusinessStore.positive(b, "actualValue"));
    if (v.getPayValue() > v.getActualValue() || v.getPayValue() > 100000000)
      throw new BusinessException(400, "INVALID_PRICE", "购买价必须不高于券面额");
    v.setType(type);
    v.setStatus(1);
    vouchers.insert(v);
    if (type == 1) {
      long start = epoch(b.get("beginTime")), end = epoch(b.get("endTime"));
      if (start == 0 || end <= start || end <= Instant.now().getEpochSecond())
        throw new BusinessException(400, "INVALID_ACTIVITY", "秒杀时间范围不正确");
      long stock = BusinessStore.positive(b, "stock");
      if (stock > 1000000) throw new BusinessException(400, "INVALID_STOCK", "库存过大");
      db.jdbc.update(
          "INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(?,?,FROM_UNIXTIME(?),FROM_UNIXTIME(?))",
          v.getId(),
          stock,
          start,
          end);
    }
    policy(v.getId(), b, type == 1 ? 0 : 1);
    String event = events.create("CATALOG_SYNC", shop);
    events.afterCommit(event, "CATALOG_SYNC");
    return v.getId();
  }

  private void policy(long voucher, Map<String, Object> b, int ready) {
    Object days = b.get("validDays");
    long until = epoch(b.get("validUntil"));
    if (days == null && until == 0) days = 30;
    long d =
        days == null
            ? 0
            : BusinessStore.number(Collections.singletonMap("validDays", days), "validDays");
    if (d < 0
        || d > 3650
        || days != null && d == 0
        || until > 0 && until <= Instant.now().getEpochSecond())
      throw new BusinessException(400, "INVALID_VALIDITY", "券使用有效期不正确");
    long min = BusinessStore.number(b, "minSpend");
    if (min < 0) throw new BusinessException(400, "INVALID_RULE", "最低消费不正确");
    int refundable = Boolean.FALSE.equals(b.get("refundAllowed")) ? 0 : 1;
    db.jdbc.update(
        "INSERT INTO tb_voucher_policy(voucher_id,valid_days,valid_until,min_spend,refund_allowed,ready) VALUES(?,?,FROM_UNIXTIME(?),?,?,?) ON DUPLICATE KEY UPDATE valid_days=VALUES(valid_days),valid_until=VALUES(valid_until),min_spend=VALUES(min_spend),refund_allowed=VALUES(refund_allowed)",
        voucher,
        days,
        until == 0 ? null : until,
        min,
        refundable,
        ready);
  }

  private Map<String, Object> mergePolicy(long id, Map<String, Object> changes) {
    Map<String, Object> old = db.one("SELECT * FROM tb_voucher_policy WHERE voucher_id=?", id);
    Map<String, Object> merged = new LinkedHashMap<>();
    if (old != null) {
      merged.put("validDays", old.get("valid_days"));
      merged.put(
          "validUntil",
          old.get("valid_until") == null
              ? null
              : ((java.sql.Timestamp) old.get("valid_until")).toInstant().toString());
      merged.put("minSpend", old.get("min_spend"));
      merged.put("refundAllowed", BusinessStore.number(old, "refund_allowed") == 1);
    }
    merged.putAll(changes);
    return merged;
  }

  @Transactional
  public void updateVoucher(long id, Map<String, Object> b) {
    Voucher v = vouchers.selectForUpdate(id);
    if (v == null) throw new BusinessException(404, "VOUCHER_NOT_FOUND", "优惠券不存在");
    access.shop(v.getShopId());
    if (b.containsKey("type") && BusinessStore.number(b, "type") != v.getType())
      throw new BusinessException(409, "TYPE_IMMUTABLE", "已创建优惠券不能修改类型");
    if (b.containsKey("title")) v.setTitle(BusinessStore.required(b, "title", 255));
    if (b.containsKey("rules")) v.setRules(BusinessStore.required(b, "rules", 1024));
    if (b.containsKey("payValue")) v.setPayValue(BusinessStore.positive(b, "payValue"));
    if (b.containsKey("actualValue")) v.setActualValue(BusinessStore.positive(b, "actualValue"));
    if (v.getPayValue() > v.getActualValue())
      throw new BusinessException(400, "INVALID_PRICE", "购买价不能高于面额");
    vouchers.updateById(v);
    if (b.containsKey("validDays")
        || b.containsKey("validUntil")
        || b.containsKey("minSpend")
        || b.containsKey("refundAllowed")) policy(id, mergePolicy(id, b), 1);
    String event = events.create("CATALOG_SYNC", v.getShopId());
    events.afterCommit(event, "CATALOG_SYNC");
  }

  @Transactional
  public void voucherStatus(long id, int status) {
    Voucher v = vouchers.selectForUpdate(id);
    if (v == null) throw new BusinessException(404, "VOUCHER_NOT_FOUND", "优惠券不存在");
    access.shop(v.getShopId());
    if (status != 1 && status != 2) throw new BusinessException(400, "INVALID_STATUS", "券状态不正确");
    v.setStatus(status);
    vouchers.updateById(v);
    String event = events.create("CATALOG_SYNC", v.getShopId());
    events.afterCommit(event, "CATALOG_SYNC");
  }
  // ready=0 的新活动尚不能下单，所以首次 Redis 库存必须严格等于 DB 库存。
  // 初始化失败留在持久事件里；ready=1 后不再用 DB 值覆盖合法预占中的 Redis 库存。
  public void sync(long shop, String eventId) {
    for (Map<String, Object> v :
        db.jdbc.queryForList(
            "SELECT s.voucher_id,s.stock FROM tb_seckill_voucher s JOIN tb_voucher v ON v.id=s.voucher_id JOIN tb_voucher_policy p ON p.voucher_id=v.id WHERE v.shop_id=? AND p.ready=0",
            shop)) {
      String key = "seckill:stock:" + v.get("voucher_id");
      redis.opsForValue().setIfAbsent(key, v.get("stock").toString());
      String current = redis.opsForValue().get(key);
      if (current == null
          || !current.matches("[0-9]+")
          || Long.parseLong(current) != BusinessStore.number(v, "stock"))
        throw new IllegalStateException("Invalid initial stock");
      db.jdbc.update(
          "UPDATE tb_voucher_policy SET ready=1 WHERE voucher_id=?", v.get("voucher_id"));
    }
    queries.syncGeo(shop);
    redis.execute(
        INVALIDATE,
        Arrays.asList(
            "cache:shop:" + shop,
            "cache:shop:logical:" + shop,
            "cache:shop:logical:" + shop + ":version",
            "cache:applied:" + eventId));
    cache.invalidate(shop);
  }

  public List<Map<String, Object>> shops(long user) {
    schema.require();
    String sql =
        "SELECT s.*,COALESCE(st.status,1) status FROM tb_shop s LEFT JOIN tb_shop_settings st ON st.shop_id=s.id";
    return BusinessStore.views(
        access.admin(user)
            ? db.jdbc.queryForList(sql + " ORDER BY s.id")
            : db.jdbc.queryForList(
                sql + " JOIN tb_shop_member m ON m.shop_id=s.id WHERE m.user_id=? ORDER BY s.id",
                user));
  }

  public List<Map<String, Object>> vouchers(long shop) {
    access.shop(shop);
    return BusinessStore.views(
        db.jdbc.queryForList(
            "SELECT v.*,s.stock,s.begin_time,s.end_time,p.valid_days,p.valid_until,p.min_spend,p.refund_allowed,COALESCE(p.ready,1) ready FROM tb_voucher v LEFT JOIN tb_seckill_voucher s ON s.voucher_id=v.id LEFT JOIN tb_voucher_policy p ON p.voucher_id=v.id WHERE v.shop_id=? ORDER BY v.id",
            shop));
  }

  public Map<String, Object> stats(long shop) {
    access.shop(shop);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(
        "paidOrders",
        db.count(
            "SELECT COUNT(*) FROM tb_voucher_order o JOIN tb_order_detail d ON d.order_id=o.id WHERE d.shop_id=? AND o.status IN(2,3)",
            shop));
    out.put("redemptions", db.count("SELECT COUNT(*) FROM tb_redemption WHERE shop_id=?", shop));
    out.put(
        "refunds",
        db.count(
            "SELECT COUNT(*) FROM tb_refund r JOIN tb_order_detail d ON d.order_id=r.order_id WHERE d.shop_id=? AND r.status='SUCCESS'",
            shop));
    try {
      Long uv =
          redis
              .opsForHyperLogLog()
              .size("uv:shop:" + shop + ":" + LocalDate.now(ZoneId.of("Asia/Shanghai")));
      out.put("todayUv", uv);
      out.put("uvApproximate", true);
    } catch (Exception e) {
      out.put("todayUv", null);
    }
    return out;
  }
}
