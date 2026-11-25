package loadtest;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;
import java.util.*;

/** Parses/binds saved YAML only. Does not instantiate Redis, Kafka, JDBC, or Spring application beans. */
public final class VerifyStaticConfiguration {
    private static ConfigurableEnvironment configuration(boolean loadtest) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        Map<String,Object> placeholders=new HashMap<>(); placeholders.put("LT_RUN_ID","STATIC_VALIDATION");
        placeholders.put("LT_MYSQL_APP_PASSWORD","STATIC_VALIDATION_PLACEHOLDER");
        env.getPropertySources().addLast(new MapPropertySource("synthetic-validation-only",placeholders));
        YamlPropertySourceLoader loader=new YamlPropertySourceLoader();
        if(loadtest) for(PropertySource<?> source:loader.load("loadtest",new FileSystemResource("src/main/resources/application-loadtest.yml"))) env.getPropertySources().addLast(source);
        for(PropertySource<?> source:loader.load("default",new FileSystemResource("src/main/resources/application.yaml"))) env.getPropertySources().addLast(source);
        return env;
    }
    public static void main(String[] args) throws Exception {
        ConfigurableEnvironment development=configuration(false), loadtest=configuration(true);
        RedisProperties dev=Binder.get(development).bind("spring.redis",Bindable.of(RedisProperties.class)).get();
        RedisProperties test=Binder.get(loadtest).bind("spring.redis",Bindable.of(RedisProperties.class)).get();
        if(!"localhost".equals(dev.getHost()) || dev.getPort()!=6379 || !"127.0.0.1".equals(test.getHost())
                || test.getPort()!=6380 || test.getDatabase()!=0 || test.isSsl() || test.getSentinel()!=null || test.getCluster()!=null
                || !"".equals(test.getPassword()) || !"".equals(test.getUrl())) throw new IllegalStateException("Redis binding isolation failed");
        String db=loadtest.getProperty("spring.datasource.url");
        if(db==null || !db.contains("127.0.0.1:3307/hmdp_loadtest") || !"loadtest-orders-STATIC_VALIDATION".equals(loadtest.getProperty("spring.kafka.consumer.group-id"))
                || !"loadtest-seckill-STATIC_VALIDATION".equals(loadtest.getProperty("hmdp.seckill.topic"))
                || !"loadtest:outbox:STATIC_VALIDATION".equals(loadtest.getProperty("hmdp.seckill.outbox"))) throw new IllegalStateException("Profile placeholder resolution failed");
        System.out.println("CONFIG_BIND_PASS defaultRedis=localhost:6379/0 loadtestRedis=127.0.0.1:6380/0 (Spring and Redisson consume the same RedisProperties)");
        System.out.println("PROFILE_PARSE_PASS datasource=isolated topic/group/outbox=run-scoped serverPort="+loadtest.getProperty("server.port"));
    }
}
