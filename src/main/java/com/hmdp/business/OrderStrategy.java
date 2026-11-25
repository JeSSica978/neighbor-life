package com.hmdp.business;

import com.hmdp.dto.Result;

/** 普通券靠请求号幂等、可多次买；秒杀券靠 Lua 限购和 Kafka 异步落库，不能混用。 */
public interface OrderStrategy {
  int type();

  Result purchase(long voucher, String request);
}
