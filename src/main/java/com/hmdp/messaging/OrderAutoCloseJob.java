package com.hmdp.messaging;

import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.OrderLifecycleService;
import com.hmdp.business.CommerceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

@Slf4j
@Component
public class OrderAutoCloseJob {
    @Resource private VoucherOrderMapper orders;
    @Resource private OrderLifecycleService lifecycle;
    @Resource private CommerceService commerce;

    @Scheduled(fixedDelayString = "${hmdp.order.scan-delay-ms:60000}", initialDelayString = "${hmdp.order.scan-delay-ms:60000}")
    public void scan() {
        if (!lifecycle.isAvailable()) return;
        for (Long id : commerce.expired()) {
            try { lifecycle.close(id, null, true); }
            catch (Exception failure) { log.error("ORDER_CLOSE_RETRY orderId={} errorType={}", id, failure.getClass().getSimpleName()); }
        }
    }
}
