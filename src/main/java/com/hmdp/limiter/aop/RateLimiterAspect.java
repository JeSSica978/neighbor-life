package com.hmdp.limiter.aop;

import com.hmdp.limiter.annotation.RateLimiter;
import com.hmdp.limiter.exception.RateLimitException;
import com.hmdp.utils.UserHolder;
import java.util.Collections;
import java.util.UUID;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 注解决定保护哪个接口，Lua 原子完成“清窗口、计数、登记”，多实例共用 Redis。 */
@Aspect
@Component
public class RateLimiterAspect {
  @Resource private StringRedisTemplate stringRedisTemplate;
  @Resource private Environment environment;
  private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();

  static {
    SCRIPT.setLocation(new ClassPathResource("limiter.lua"));
    SCRIPT.setResultType(Long.class);
  }

  @Before("@annotation(rule)")
  public void before(JoinPoint point, RateLimiter rule) {
    String prefix = "hmdp.rate." + rule.config() + ".";
    long window =
        rule.config().isEmpty()
            ? rule.window()
            : environment.getProperty(prefix + "window", Long.class, (long) rule.window());
    long limit =
        rule.config().isEmpty()
            ? rule.limit()
            : environment.getProperty(prefix + "limit", Long.class, (long) rule.limit());
    if (window < 1 || limit < 1)
      throw new IllegalStateException("Invalid rate limit configuration");
    String method = ((MethodSignature) point.getSignature()).getMethod().toGenericString();
    HttpServletRequest request =
        ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    String identity = "global";
    // 身份只取登录拦截器校验过的 UserHolder；未认证请求按真实连接 IP 分组。
    if (rule.type() == RateLimiter.LimitType.USER && UserHolder.getUser() != null)
      identity = "user:" + UserHolder.getUser().getId();
    else if (rule.type() != RateLimiter.LimitType.METHOD)
      identity = "ip:" + request.getRemoteAddr();
    // 未配置可信反向代理时不读取 X-Forwarded-For，防止调用者伪造 IP 绕过限流。
    Long allowed = executeSlidingWindowScript(rule.key() + method + ":" + identity, window, limit);
    if (allowed == null) throw new IllegalStateException("Missing limiter result");
    if (allowed == 0) throw new RateLimitException(rule.message());
  }

  public Long executeSlidingWindowScript(String key, Long window, Long limit) {
    return stringRedisTemplate.execute(
        SCRIPT,
        Collections.singletonList(key),
        window.toString(),
        limit.toString(),
        UUID.randomUUID().toString());
  }
}
