package com.hmdp.service.cache;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.*;
import com.hmdp.config.VoucherCacheProperties;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class VoucherListCacheService {
  // Caffeine/Redis 只存静态商品资料；每次响应另读 Redis 库存并复制 DTO，防止 L1 留住旧库存。
  @Resource private VoucherMapper voucherMapper;
  @Resource private StringRedisTemplate redis;
  @Resource private VoucherCacheProperties props;
  private LoadingCache<String, List<Voucher>> l1;

  @PostConstruct
  public void initCache() {
    l1 =
        Caffeine.newBuilder()
            .maximumSize(1000)
            .refreshAfterWrite(props.getRefreshAfterWrite().toMillis(), TimeUnit.MILLISECONDS)
            .expireAfterWrite(props.getExpireAfterWrite().toMillis(), TimeUnit.MILLISECONDS)
            .build(k -> load(Long.parseLong(k.split(":")[0]), k.split(":")[1]));
  }

  private List<Voucher> db(long shop) {
    List<Voucher> rows = voucherMapper.queryVoucherOfShop(shop);
    if (rows == null) return Collections.emptyList();
    for (Voucher v : rows) v.setStock(null);
    return rows;
  }

  private List<Voucher> load(long shop, String version) {
    String key = "cache:voucher:list:" + shop + ":v:" + version;
    String raw = redis.opsForValue().get(key);
    if (raw != null) return JSONUtil.toList(raw, Voucher.class);
    List<Voucher> rows = db(shop);
    redis
        .opsForValue()
        .set(
            key,
            JSONUtil.toJsonStr(rows),
            rows.isEmpty() ? 120000 : props.getRedisTtl().toMillis(),
            TimeUnit.MILLISECONDS);
    return rows;
  }

  public List<Voucher> getVoucherByShopId(Long shop) {
    List<Voucher> rows;
    try {
      String v = redis.opsForValue().get("cache:voucher:version:" + shop);
      if (v == null) v = "0";
      // 所有实例先读共享版本，版本变化便使用新的 L1/L2 key。
      // 旧请求即使晚到回填，也只能写旧版本 key；其他实例无需依赖 Kafka 广播来清 L1。
      if ("mysql".equalsIgnoreCase(props.getMode())) rows = db(shop);
      else if ("redis".equalsIgnoreCase(props.getMode())) rows = load(shop, v);
      else rows = l1.get(shop + ":" + v);
    } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
      rows = db(shop);
    }
    List<Voucher> out = new ArrayList<>();
    for (Voucher source : rows) {
      Voucher v = BeanUtil.copyProperties(source, Voucher.class);
      if (Integer.valueOf(1).equals(v.getType())) {
        try {
          String stock = redis.opsForValue().get("seckill:stock:" + v.getId());
          v.setStock(stock == null ? 0 : Integer.parseInt(stock));
          if (stock == null) v.setReady(false);
        } catch (RuntimeException e) {
          v.setStock(0);
          v.setReady(false);
        }
      }
      out.add(v);
    }
    return out;
  }

  public void invalidate(Long shop) {
    redis.opsForValue().increment("cache:voucher:version:" + shop);
    redis.delete("cache:voucher:list:" + shop);
    l1.invalidateAll();
  }
}
