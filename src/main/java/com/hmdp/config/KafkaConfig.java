package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.SeekToCurrentErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import java.util.HashMap;
import java.util.Map;

/**
 * Uses the Spring Boot-managed Kafka version. Failed records are never recovered by skipping.
 */
@Slf4j
@Configuration
public class KafkaConfig {
    @Bean
    public NewTopic shopCacheDeleteTopic(@Value("${hmdp.reliable.cache-topic:shop-cache-delete}") String topic) {
        return reliableTopic(topic);
    }
    @Bean
    public NewTopic orderStockRestoreTopic(@Value("${hmdp.reliable.stock-topic:order-stock-restore}") String topic) {
        return reliableTopic(topic);
    }
    private NewTopic reliableTopic(String topic) {
        Map<String, String> configuration = new HashMap<>();
        configuration.put("retention.ms", "-1");
        configuration.put("retention.bytes", "-1");
        return new NewTopic(topic, 1, (short) 1).configs(configuration);
    }
    @Bean
    public NewTopic seckillOrderTopic(@Value("${hmdp.seckill.topic:seckill-voucher-order}") String topic) {
        Map<String, String> configuration = new HashMap<>();
        configuration.put("retention.ms", "-1");
        configuration.put("retention.bytes", "-1");
        configuration.put("cleanup.policy", "delete");
        return new NewTopic(topic, 1, (short) 1).configs(configuration);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> seckillVoucherOrderKafkaListenerContainerFactory(
            KafkaProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties()));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        SeekToCurrentErrorHandler handler = new SeekToCurrentErrorHandler((record, exception) -> {
            log.error("KAFKA_RECORD_BLOCKED topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset(), exception);
            // Even a non-retryable/deserialization failure must not silently advance the offset.
            throw new IllegalStateException("Message requires repair; refusing to discard Kafka record", exception);
        }, new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        factory.setErrorHandler(handler);
        return factory;
    }
}
