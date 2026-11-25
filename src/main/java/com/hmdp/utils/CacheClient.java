package com.hmdp.utils;

import cn.hutool.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import javax.annotation.PreDestroy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class CacheClient {
  // 锁的值是本次请求的 owner，解锁时比较它，避免超时后误删别人刚取得的锁。
  private final StringRedisTemplate redis;
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          4,
          60,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(100),
          new ThreadPoolExecutor.AbortPolicy());
  private static final DefaultRedisScript<Long> UNLOCK =
      new DefaultRedisScript<>(
          "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end;return 0",
          Long.class);
  // 读 DB 前记下版本，回填时用 Lua 校验。商品更新推进版本后，旧读不能再写回旧数据。
  private static final DefaultRedisScript<Long> WRITE =
      new DefaultRedisScript<>(
          "if (redis.call('GET',KEYS[2]) or '0')==ARGV[1] then redis.call('SET',KEYS[1],ARGV[2],'PX',ARGV[3]);return 1 end;return 0",
          Long.class);

  public CacheClient(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void set(String key, Object value, Long time, TimeUnit unit) {
    redis.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
  }

  public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
    write(key, value, version(key), time, unit);
  }

  private String version(String key) {
    String v = redis.opsForValue().get(key + ":version");
    return v == null ? "0" : v;
  }

  private void write(String key, Object value, String version, long time, TimeUnit unit) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", value);
    body.put("missing", value == null);
    body.put(
        "expireEpoch", System.currentTimeMillis() + (value == null ? 120000 : unit.toMillis(time)));
    body.put("version", version);
    redis.execute(
        WRITE,
        Arrays.asList(key, key + ":version"),
        version,
        JSONUtil.toJsonStr(body),
        Long.toString(Math.max(120000, TimeUnit.MINUTES.toMillis(30))));
  }

  public <R, ID> R queryWithPassThrough(
      String prefix, ID id, Class<R> type, Function<ID, R> fallback, Long time, TimeUnit unit) {
    String key = prefix + id;
    try {
      String json = redis.opsForValue().get(key);
      if (json != null) return json.isEmpty() ? null : JSONUtil.toBean(json, type);
      R r = fallback.apply(id);
      if (r == null) redis.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
      else set(key, r, time, unit);
      return r;
    } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
      return fallback.apply(id);
    }
  }

  public <R, ID> R queryWithLogicalExpire(
      String prefix, ID id, Class<R> type, Function<ID, R> fallback, Long time, TimeUnit unit) {
    // 逻辑过期先返回已有值，并由有界线程池重建；冷启动没有旧值时才等待回源。
    String key = prefix + id;
    try {
      String v = version(key), raw = redis.opsForValue().get(key);
      if (raw != null) {
        JSONObject body = JSONUtil.parseObj(raw);
        if (v.equals(body.getStr("version"))) {
          R r =
              body.getBool("missing", false)
                  ? null
                  : JSONUtil.toBean(JSONUtil.toJsonStr(body.get("data")), type);
          if (body.getLong("expireEpoch", 0L) > System.currentTimeMillis()) return r;
          String owner = UUID.randomUUID().toString(), lock = "lock:cache:" + key;
          if (Boolean.TRUE.equals(
              redis.opsForValue().setIfAbsent(lock, owner, 10, TimeUnit.SECONDS))) {
            try {
              workers.execute(
                  () -> {
                    try {
                      write(key, fallback.apply(id), v, time, unit);
                    } finally {
                      redis.execute(UNLOCK, Collections.singletonList(lock), owner);
                    }
                  });
            } catch (RejectedExecutionException e) {
              redis.execute(UNLOCK, Collections.singletonList(lock), owner);
            }
          }
          return r;
        }
      }
      return load(prefix, id, type, fallback, time, unit, true);
    } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
      return fallback.apply(id);
    }
  }

  private <R, ID> R load(
      String prefix,
      ID id,
      Class<R> type,
      Function<ID, R> fallback,
      Long time,
      TimeUnit unit,
      boolean logical) {
    String key = prefix + id, lock = "lock:cache:" + key, owner = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < 20; attempt++) {
      if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, owner, 10, TimeUnit.SECONDS))) {
        try {
          String v = version(key);
          R r = fallback.apply(id);
          if (logical) write(key, r, v, time, unit);
          else if (r == null) redis.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
          else set(key, r, time, unit);
          return r;
        } finally {
          redis.execute(UNLOCK, Collections.singletonList(lock), owner);
        }
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      String raw = redis.opsForValue().get(key);
      if (raw != null) {
        if (logical) {
          JSONObject b = JSONUtil.parseObj(raw);
          if (version(key).equals(b.getStr("version")))
            return b.getBool("missing", false)
                ? null
                : JSONUtil.toBean(JSONUtil.toJsonStr(b.get("data")), type);
        } else return raw.isEmpty() ? null : JSONUtil.toBean(raw, type);
      }
    }
    return fallback.apply(id);
  }

  public <R, ID> R queryWithMutex(
      String prefix, ID id, Class<R> type, Function<ID, R> fallback, Long time, TimeUnit unit) {
    try {
      return load(prefix, id, type, fallback, time, unit, false);
    } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
      return fallback.apply(id);
    }
  }

  @PreDestroy
  public void stop() {
    workers.shutdownNow();
  }
}
