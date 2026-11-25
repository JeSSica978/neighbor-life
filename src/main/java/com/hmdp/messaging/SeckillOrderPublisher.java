package com.hmdp.messaging;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Redis entries remain until Kafka acknowledges; a crash in between causes a safe duplicate. */
@Slf4j
@Component
public class SeckillOrderPublisher {
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private KafkaTemplate<String, String> kafkaTemplate;
    @Resource private RedissonClient redissonClient;
    @Resource private ObjectMapper objectMapper;
    @Value("${hmdp.seckill.topic:seckill-voucher-order}") private String topic;
    @Value("${hmdp.seckill.outbox:seckill:outbox}") private String outbox;

    @Scheduled(fixedDelayString = "${hmdp.seckill.publish-delay-ms:1000}")
    public void publishPendingOrders() {
        RLock lock = redissonClient.getLock("lock:seckill:outbox");
        if (!lock.tryLock()) return;
        try {
            List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().range(
                    outbox, Range.unbounded(), Limit.limit().count(20));
            if (records == null) return;
            for (MapRecord<String, Object, Object> record : records) {
                try {
                    String json = objectMapper.writeValueAsString(record.getValue());
                    String voucherId = String.valueOf(record.getValue().get("voucherId"));
                    SendResult<String, String> sent = kafkaTemplate.send(topic, voucherId, json).get(10, TimeUnit.SECONDS);
                    log.info("SECKILL_KAFKA_SENT orderId={} topic={} partition={} offset={}",
                            record.getValue().get("id"), sent.getRecordMetadata().topic(),
                            sent.getRecordMetadata().partition(), sent.getRecordMetadata().offset());
                    // 等 Kafka 确认后才删 outbox；确认后、删除前崩溃只会造成安全的重复消息。
                    stringRedisTemplate.opsForStream().delete(outbox, record.getId());
                } catch (Exception exception) {
                    if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                    log.error("SECKILL_OUTBOX_RETRY streamId={} retained=true", record.getId(), exception);
                    break;
                }
            }
        } catch (Exception exception) {
            log.error("SECKILL_OUTBOX_UNAVAILABLE entries retained", exception);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
