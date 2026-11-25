package com.hmdp.consumer;

import com.hmdp.service.ReliableEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

@Component
public class ReliableEventConsumer {
    @Resource private ReliableEventService events;

    @KafkaListener(id="shopCacheDelete", topics="${hmdp.reliable.cache-topic:shop-cache-delete}",
            groupId="${hmdp.reliable.cache-group:neighborlife-cache-compensation}",
            containerFactory="seckillVoucherOrderKafkaListenerContainerFactory")
    public void deleteCache(ConsumerRecord<String, String> record) { consume(record, "CACHE_FAMILY"); }

    @KafkaListener(id="orderStockRestore", topics="${hmdp.reliable.stock-topic:order-stock-restore}",
            groupId="${hmdp.reliable.stock-group:neighborlife-stock-compensation}",
            containerFactory="seckillVoucherOrderKafkaListenerContainerFactory")
    public void restoreStock(ConsumerRecord<String, String> record) { consume(record, ReliableEventService.STOCK_RESTORE); }

    private void consume(ConsumerRecord<String, String> record, String type) {
        try { events.process(record.value(), type); }
        catch (Exception failure) {
            if (!events.recordFailure(record.value(), type, failure)) throw new IllegalStateException("Retry reliable event", failure);
        }
        // RECORD commit happens after process() committed DONE, or BLOCKED was durably saved.
    }
}
