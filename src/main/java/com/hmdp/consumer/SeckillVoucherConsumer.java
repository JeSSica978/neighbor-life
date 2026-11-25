package com.hmdp.consumer;

import com.hmdp.entity.VoucherOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.SeckillConstants;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Slf4j
@Component
public class SeckillVoucherConsumer {
    private static final DefaultRedisScript<Long> MARK_CREATED = new DefaultRedisScript<>(
            "if redis.call('HGET',KEYS[1],'state') == 'PENDING' then " +
            "redis.call('HSET',KEYS[1],'state','CREATED'); redis.call('EXPIRE',KEYS[1],604800); end; return 1", Long.class);

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource private ObjectMapper objectMapper;
    @Resource private StringRedisTemplate stringRedisTemplate;

    /**
     * 【秒杀链路消息队列使用】-3
     * Kafka 监听消费秒杀订单消息
     * 指定之前创建的containerFactory
     */
    @KafkaListener(
            id = "seckillVoucherOrders",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "seckillVoucherOrderKafkaListenerContainerFactory",
            topics = "${hmdp.seckill.topic:seckill-voucher-order}"
    )
    public void processMessage(ConsumerRecord<String, String> record) {
        try {
            JsonNode json = objectMapper.readTree(record.value());
            VoucherOrder order = new VoucherOrder();
            order.setId(positiveId(json, "id"));
            order.setUserId(positiveId(json, "userId"));
            order.setVoucherId(positiveId(json, "voucherId"));
            // 必须调用 Spring 事务代理；方法成功返回才说明 DB 扣库存和建单一起提交。
            voucherOrderService.createVoucherOrder(order);
            String reservation = SeckillConstants.RESERVATION + order.getId();
            stringRedisTemplate.execute(MARK_CREATED, Collections.singletonList(reservation));
            log.info("SECKILL_CONSUMED orderId={} topic={} partition={} offset={} transactionCommitted=true thread={}",
                    order.getId(), record.topic(), record.partition(), record.offset(), Thread.currentThread().getName());
            // RECORD 模式在监听方法成功返回后提交 offset；异常抛出时保留消息重试。
        } catch (Exception e) {
            log.error("SECKILL_CONSUME_RETRY topic={} partition={} offset={} offsetNotAcknowledged=true",
                    record.topic(), record.partition(), record.offset(), e);
            throw new IllegalStateException("Order processing failed; retain and retry record", e);
        }
    }

    private Long positiveId(JsonNode json, String field) {
        if (json == null || !json.hasNonNull(field)) throw new IllegalArgumentException("Missing " + field);
        long value = Long.parseLong(json.get(field).asText());
        if (value <= 0) throw new IllegalArgumentException("Invalid " + field);
        return value;
    }
}
