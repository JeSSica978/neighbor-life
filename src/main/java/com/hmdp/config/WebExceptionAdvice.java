package com.hmdp.config;

import com.hmdp.business.BusinessException;
import com.hmdp.limiter.exception.RateLimitException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class WebExceptionAdvice {
  private static final Logger log = LoggerFactory.getLogger(WebExceptionAdvice.class);

  private ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("success", false);
    b.put("code", code);
    b.put("errorMsg", message);
    return ResponseEntity.status(status).body(b);
  }

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<Map<String, Object>> business(BusinessException e) {
    return error(e.getStatus(), e.getCode(), e.getMessage());
  }

  @ExceptionHandler(RateLimitException.class)
  public ResponseEntity<Map<String, Object>> rate(RateLimitException e) {
    return error(429, "RATE_LIMITED", e.getMessage());
  }

  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  public ResponseEntity<Map<String, Object>> duplicate(Exception e) {
    return error(409, "DUPLICATE_REQUEST", "请求编号或业务记录已存在，请查询原结果");
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<Map<String, Object>> input(Exception e) {
    return error(400, "INVALID_INPUT", "请求格式或参数不正确");
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, Object>> unexpected(RuntimeException e) {
    String id = UUID.randomUUID().toString();
    log.error("BUSINESS_ERROR reference={} type={}", id, e.getClass().getSimpleName(), e);
    return error(500, "SERVER_ERROR", "服务暂时不可用，查询编号：" + id);
  }
}
