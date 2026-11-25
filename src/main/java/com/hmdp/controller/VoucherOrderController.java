package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource private com.hmdp.business.OrderStrategyFactory strategies;

    @com.hmdp.limiter.annotation.RateLimiter(type=com.hmdp.limiter.annotation.RateLimiter.LimitType.USER,window=5,limit=20,config="purchase")
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return strategies.get(1).purchase(voucherId,null);
    }

    @com.hmdp.limiter.annotation.RateLimiter(type=com.hmdp.limiter.annotation.RateLimiter.LimitType.USER,window=5,limit=20,config="purchase")
    @PostMapping("normal/{id}")
    public Result purchaseNormalVoucher(@PathVariable("id") Long voucherId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return strategies.get(0).purchase(voucherId, requestId);
    }

    @GetMapping("/{id}")
    public Result queryOrderStatus(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrderStatus(orderId);
    }

    @GetMapping("/mine")
    public Result listMyOrders(@RequestParam(value="page", defaultValue="1") int page) {
        return voucherOrderService.listMyOrders(page);
    }

    @PostMapping("/{id}/pay/simulate")
    public Result simulatePayment(@PathVariable("id") Long id) { return voucherOrderService.simulatePayment(id); }

    @PostMapping("/{id}/cancel")
    public Result cancel(@PathVariable("id") Long id) { return voucherOrderService.cancelOrder(id); }
}
