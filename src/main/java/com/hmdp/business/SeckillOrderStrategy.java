package com.hmdp.business;

import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import javax.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class SeckillOrderStrategy implements OrderStrategy {
  @Resource private IVoucherOrderService orders;

  public int type() {
    return 1;
  }

  public Result purchase(long voucher, String request) {
    return orders.seckillVoucher(voucher);
  }
}
