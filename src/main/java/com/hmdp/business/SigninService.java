package com.hmdp.business;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.annotation.Resource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SigninService {
  @Resource private StringRedisTemplate redis;

  private String key(long user, YearMonth month) {
    return "sign:" + user + month.format(DateTimeFormatter.ofPattern(":yyyyMM"));
  }

  private long bits(long user, YearMonth month, int days) {
    List<Long> b =
        redis
            .opsForValue()
            .bitField(
                key(user, month),
                BitFieldSubCommands.create()
                    .get(BitFieldSubCommands.BitFieldType.unsigned(days))
                    .valueAt(0));
    return b == null || b.isEmpty() || b.get(0) == null ? 0 : b.get(0);
  }

  public Map<String, Object> sign(long user) {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    // 日期从 1 开始，位偏移从 0 开始；重复签到只是把同一位设为 1，天然幂等。
    redis.opsForValue().setBit(key(user, YearMonth.from(today)), today.getDayOfMonth() - 1, true);
    return month(user, YearMonth.from(today).toString());
  }
  // BITFIELD 按天取位，最低位对应当前读取区间的最后一天；右移逐天数连续的 1。
  // 本月全连续才回溯上月，因此月初不会错误地把连续签到清零。
  public int streak(long user) {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    YearMonth m = YearMonth.from(today);
    int days = today.getDayOfMonth(), total = 0;
    for (int i = 0; i < 120; i++) {
      long n = bits(user, m, days);
      int continuous = 0;
      while (continuous < days && (n & 1) == 1) {
        continuous++;
        n >>>= 1;
      }
      total += continuous;
      if (continuous < days) break;
      m = m.minusMonths(1);
      days = m.lengthOfMonth();
    }
    return total;
  }

  public Map<String, Object> month(long user, String value) {
    YearMonth m;
    try {
      m = YearMonth.parse(value);
    } catch (Exception e) {
      throw new BusinessException(400, "INVALID_MONTH", "月份格式应为 yyyy-MM");
    }
    long n = bits(user, m, m.lengthOfMonth());
    List<Integer> days = new ArrayList<>();
    for (int d = 1; d <= m.lengthOfMonth(); d++)
      if ((n & (1L << (m.lengthOfMonth() - d))) != 0) days.add(d);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("month", m.toString());
    out.put("signedDays", days);
    out.put("continuousDays", streak(user));
    out.put("today", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
    return out;
  }
}
