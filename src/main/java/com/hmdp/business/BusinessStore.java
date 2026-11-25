package com.hmdp.business;

import com.hmdp.utils.UserHolder;
import java.sql.Timestamp;
import java.util.*;
import javax.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BusinessStore {
  @Resource public JdbcTemplate jdbc;

  public Map<String, Object> one(String sql, Object... args) {
    List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public long count(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  public static long number(Map<String, Object> m, String key) {
    Object v = m == null ? null : m.get(key);
    try {
      return v == null ? 0 : Long.parseLong(v.toString());
    } catch (NumberFormatException e) {
      throw new BusinessException(400, "INVALID_NUMBER", key + " 必须为整数");
    }
  }

  public static String string(Map<String, Object> m, String key) {
    Object v = m == null ? null : m.get(key);
    return v == null ? "" : v.toString();
  }

  public static long user() {
    if (UserHolder.getUser() == null) throw new BusinessException(401, "LOGIN_REQUIRED", "请先登录");
    return UserHolder.getUser().getId();
  }

  public static String key(String key) {
    if (key == null || key.isEmpty()) return UUID.randomUUID().toString();
    if (!key.matches("[A-Za-z0-9_-]{8,64}"))
      throw new BusinessException(400, "INVALID_REQUEST_KEY", "请求编号须为 8–64 位字母、数字、下划线或横线");
    return key;
  }

  public static String required(Map<String, Object> body, String key, int max) {
    String s = string(body, key).trim();
    if (s.isEmpty() || s.length() > max)
      throw new BusinessException(400, "INVALID_INPUT", key + " 长度不正确");
    return s;
  }

  public static long positive(Map<String, Object> body, String key) {
    long n;
    try {
      n = number(body, key);
    } catch (RuntimeException e) {
      n = 0;
    }
    if (n <= 0) throw new BusinessException(400, "INVALID_INPUT", key + " 必须为正数");
    return n;
  }

  public static void page(int page, int size) {
    if (page < 1 || page > 10000 || size < 1 || size > 48)
      throw new BusinessException(400, "INVALID_PAGE", "分页参数不正确");
  }

  public static Map<String, Object> view(Map<String, Object> raw) {
    if (raw == null) return null;
    Map<String, Object> v = new LinkedHashMap<>();
    raw.forEach(
        (k, x) -> {
          StringBuilder camel = new StringBuilder();
          boolean upper = false;
          for (char c : k.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') {
              upper = true;
              continue;
            }
            camel.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
          }
          if (x != null && (k.equalsIgnoreCase("id") || k.toLowerCase(Locale.ROOT).endsWith("_id")))
            x = x.toString();
          if (x instanceof Timestamp) x = ((Timestamp) x).toInstant().toString();
          v.put(camel.toString(), x);
        });
    return v;
  }

  public static List<Map<String, Object>> views(List<Map<String, Object>> rows) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> r : rows) out.add(view(r));
    return out;
  }
}
