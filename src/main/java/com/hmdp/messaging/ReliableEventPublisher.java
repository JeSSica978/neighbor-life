package com.hmdp.messaging;

import com.hmdp.entity.ReliableEvent;
import com.hmdp.mapper.ReliableEventMapper;
import com.hmdp.service.ReliableEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ReliableEventPublisher {
    @Resource private ReliableEventMapper events;
    @Resource private ReliableEventService readiness;
    @Resource private KafkaTemplate<String, String> kafka;
    @Value("${hmdp.reliable.cache-topic:shop-cache-delete}") private String cacheTopic;
    @Value("${hmdp.reliable.stock-topic:order-stock-restore}") private String stockTopic;

    @Scheduled(fixedDelayString = "${hmdp.reliable.publish-delay-ms:1000}", initialDelayString = "${hmdp.reliable.publish-delay-ms:1000}")
    public void publish() {
        if (!readiness.isAvailable()) return;
        for (ReliableEvent event : events.dispatchable()) {
            try {
                String topic;
                if (ReliableEventService.cacheType(event.getEventType())) topic = cacheTopic;
                else if (ReliableEventService.STOCK_RESTORE.equals(event.getEventType())) topic = stockTopic;
                else throw new IllegalArgumentException("Unsupported reliable event");
                // A String UUID, not a serialized Java object. Canonical operation stays in MySQL.
                kafka.send(topic, event.getResourceId().toString(), event.getId()).get(5, TimeUnit.SECONDS);
                events.sent(event.getId()); // WHERE status IN (0,1) cannot overwrite a fast consumer's DONE
                log.info("RELIABLE_EVENT_SENT eventId={} type={}", event.getId(), event.getEventType());
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                events.sendFailed(event.getId(), failure.getClass().getSimpleName());
                log.warn("RELIABLE_SEND_RETRY eventId={} attempts={} errorType={}", event.getId(),
                        event.getSendAttempts() + 1, failure.getClass().getSimpleName());
                break; // avoid holding the shared scheduler for every pending record during an outage
            }
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void alertBlocked() {
        if (!readiness.isAvailable()) return;
        Integer count = events.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReliableEvent>().eq("status", 3));
        if (count > 0) log.error("RELIABLE_EVENT_ALERT blockedCount={} repairRequired=true", count);
    }
}
