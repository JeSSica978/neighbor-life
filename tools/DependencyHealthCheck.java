import java.io.InputStream;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.yaml.snakeyaml.Yaml;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;

/** Small startup checks using the root application's actual runtime dependencies. */
public class DependencyHealthCheck {
    private static Map<String, Object> config;
    private static int failures;
    private static String setting(String path, String fallback) {
        String environment = System.getenv(path.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        if (environment != null) return environment;
        Object cursor = config;
        for (String part : path.split("\\.")) {
            if (!(cursor instanceof Map)) return fallback;
            cursor = ((Map<?, ?>) cursor).get(part);
        }
        return cursor == null ? fallback : String.valueOf(cursor);
    }
    private static void fail(String check, Exception exception) {
        failures++;
        // Exception messages may contain credentials: print only their types.
        System.out.println(check + "_FAIL exception=" + exception.getClass().getSimpleName());
    }
    public static void main(String[] args) throws Exception {
        try (InputStream input = Files.newInputStream(Paths.get("src/main/resources/application.yaml"))) {
            config = new Yaml().load(input);
        }
        if (!setting("spring.profiles.active", "").isEmpty()
                || System.getenv("SPRING_CONFIG_LOCATION") != null
                || System.getenv("SPRING_CONFIG_ADDITIONAL_LOCATION") != null) {
            System.out.println("CONFIG_FAIL: The checker supports the current root default configuration only. Review profile/config overrides before launch.");
            System.exit(1);
        }
        DriverManager.setLoginTimeout(3);
        String url = setting("spring.datasource.url", "");
        url += (url.contains("?") ? "&" : "?") + "connectTimeout=3000&socketTimeout=3000";
        try (Connection connection = DriverManager.getConnection(url,
                setting("spring.datasource.username", ""), setting("spring.datasource.password", ""))) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM tb_shop_type")) {
                result.next();
                System.out.println("MYSQL_HEALTH_PASS categories=" + result.getLong(1));
            }
        } catch (Exception exception) { fail("MYSQL_HEALTH", exception); }

        RedisURI.Builder builder = RedisURI.Builder.redis(setting("spring.redis.host", "localhost"),
                Integer.parseInt(setting("spring.redis.port", "6379")))
                .withDatabase(Integer.parseInt(setting("spring.redis.database", "0")))
                .withTimeout(Duration.ofSeconds(3));
        String password = setting("spring.redis.password", "");
        if (!password.isEmpty()) builder.withPassword(password.toCharArray());
        RedisClient client = RedisClient.create(builder.build());
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            String key = "startup-check:neighbor-life:" + UUID.randomUUID();
            String payload = UUID.randomUUID().toString();
            boolean owned = false;
            try {
                if (!"PONG".equals(connection.sync().ping())) throw new IllegalStateException();
                owned = "OK".equals(connection.sync().set(key, payload, SetArgs.Builder.nx().ex(30)));
                if (!owned || !payload.equals(connection.sync().get(key))) throw new IllegalStateException();
                System.out.println("REDIS_HEALTH_PASS PING/SET_NX_EX30/GET");
            } finally { if (owned) connection.sync().del(key); }
        } catch (Exception exception) { fail("REDIS_HEALTH", exception); }
        finally { client.shutdown(); }

        Properties properties = new Properties();
        properties.put("bootstrap.servers", setting("spring.kafka.bootstrap-servers", "localhost:9092"));
        properties.put("request.timeout.ms", "3000");
        properties.put("default.api.timeout.ms", "5000");
        try (AdminClient admin = AdminClient.create(properties)) {
            TopicDescription topic = admin.describeTopics(Collections.singleton("seckill-voucher-order"))
                    .all().get(5, TimeUnit.SECONDS).get("seckill-voucher-order");
            if (topic.partitions().isEmpty()) throw new IllegalStateException();
            properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            properties.put("enable.auto.commit", "false");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
                if (consumer.partitionsFor("seckill-voucher-order", Duration.ofSeconds(5)).isEmpty()) throw new IllegalStateException();
            }
            System.out.println("KAFKA_HEALTH_PASS broker/topic/consumer-metadata (no business messages)");
        } catch (Exception exception) { fail("KAFKA_HEALTH", exception); }
        if (failures != 0) System.exit(1);
    }
}
