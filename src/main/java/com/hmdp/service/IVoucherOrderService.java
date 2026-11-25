package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    Result purchaseNormalVoucher(Long voucherId, String requestId);

    void createVoucherOrder(VoucherOrder voucherOrder);
    Result queryOrderStatus(Long orderId);
    Result listMyOrders(int page);
    Result simulatePayment(Long orderId);
    Result cancelOrder(Long orderId);
}
