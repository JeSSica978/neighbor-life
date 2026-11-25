package com.hmdp.business;

import java.util.List;
import javax.annotation.Resource;
import org.springframework.stereotype.Component;

/** Spring 注入全部下单策略；按券类型选择实现，普通券和秒杀券各自保留购买规则。 */
@Component
public class OrderStrategyFactory {
  @Resource private List<OrderStrategy> strategies;

  public OrderStrategy get(int type) {
    return strategies.stream()
        .filter(s -> s.type() == type)
        .findFirst()
        .orElseThrow(() -> new BusinessException(400, "INVALID_TYPE", "优惠券类型不支持"));
  }
}
