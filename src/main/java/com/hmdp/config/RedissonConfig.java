package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(RedisProperties redis){
        // 配置
        Config config = new Config();
        // Use the same effective connection settings as Spring's RedisConnectionFactory.
        if (StringUtils.hasText(redis.getUrl()) || redis.getSentinel() != null || redis.getCluster() != null) {
            throw new IllegalArgumentException("Redisson single-server configuration requires spring.redis.host/port; URL, sentinel and cluster are unsupported");
        }
        String host = redis.getHost();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        SingleServerConfig server = config.useSingleServer()
                .setAddress((redis.isSsl() ? "rediss://" : "redis://") + host + ":" + redis.getPort())
                .setDatabase(redis.getDatabase());
        if (StringUtils.hasText(redis.getPassword())) server.setPassword(redis.getPassword());
        if (redis.getTimeout() != null) server.setTimeout(Math.toIntExact(redis.getTimeout().toMillis()));
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
