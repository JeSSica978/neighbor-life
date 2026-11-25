package com.hmdp.service;
import com.hmdp.business.BusinessSchema;
import com.hmdp.business.CommerceService;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import java.util.Objects;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 兼容原订单接口；实际支付、关单和返库规则统一交给 CommerceService。 */
@Service
public class OrderLifecycleService {
  @Resource private CommerceService commerce;
  @Resource private BusinessSchema schema;
  @Resource private ReliableEventService events;
  @Resource private VoucherOrderMapper orders;

  @Value("${hmdp.order.payment-timeout-seconds:900}")
  private long timeout;

  public long timeoutSeconds() {
    return Math.max(1, timeout);
  }

  public boolean isAvailable() {
    return schema.ready() && events.isAvailable();
  }

  public VoucherOrder pay(Long id, Long user) {
    VoucherOrder order = orders.selectById(id);
    if (order == null || !Objects.equals(order.getUserId(), user)) return null;
    return commerce.payNow(id, user);
  }

  public boolean close(Long id, Long user, boolean expiredOnly) {
    // detail 快照是新订单的管理标记；旧订单即使超时，也不进入自动关单或库存补偿。
    if (!commerce.managed(id)) return false;
    return commerce.close(id, user, expiredOnly);
  }
}
