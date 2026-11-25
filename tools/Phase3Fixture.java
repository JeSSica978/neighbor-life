import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.common.TopicPartition;
import java.util.concurrent.TimeUnit;

/** Only creates/resets/deletes the fixture recorded in target/phase3-fixture-private.json. */
class Phase3Fixture {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path FILE = fixtureFile();
    static final String[] TABLES = {"tb_shop", "tb_user", "tb_blog", "tb_voucher", "tb_seckill_voucher", "tb_voucher_order"};
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static PropertySource<?> settings;
    static Map<String, Object> fixture;
    static Connection db;
    static StatefulRedisConnection<String, String> redis;
    static String setting(String key) { return System.getProperty(key, Objects.toString(settings.getProperty(key), "")); }
    static Path fixtureFile() {
        Path target = Paths.get("target").toAbsolutePath().normalize();
        Path record = Paths.get(System.getProperty("neighborlife.fixture-file", "target/phase3-fixture-private.json")).toAbsolutePath().normalize();
        if (!record.startsWith(target) || !record.getFileName().toString().startsWith("phase3-fixture-"))
            throw new IllegalArgumentException("Fixture record must stay inside this project's target directory");
        return record;
    }
    public static void main(String[] args) throws Exception {
        settings = new YamlPropertySourceLoader().load("project", new FileSystemResource("src/main/resources/application.yaml")).get(0);
        RedisClient client = RedisClient.create("redis://" + setting("spring.redis.host") + ":" + setting("spring.redis.port"));
        try (Connection connection = DriverManager.getConnection(setting("spring.datasource.url"), setting("spring.datasource.username"), setting("spring.datasource.password"));
             StatefulRedisConnection<String, String> redisConnection = client.connect()) {
            db = connection; redis = redisConnection;
            String action = args[0];
            if (action.equals("Prepare")) prepare();
            else {
                if (!Files.exists(FILE)) throw new IllegalStateException("No owned fixture; run Prepare first");
                fixture = JSON.readValue(FILE.toFile(), LinkedHashMap.class);
                verifyOwnership();
                if (action.equals("Submit")) {
                    int index = Integer.parseInt(args[1]);
                    JsonNode result = request("POST", "/voucher-order/seckill/" + displayIds().get(index), null, token());
                    System.out.println("SECKILL_RESPONSE " + result);
                } else if (action.equals("Demo")) refreshDemo();
                else if (action.equals("Reset")) reset(false);
                else if (action.equals("Cleanup")) reset(true);
                else if (!action.equals("Inspect")) throw new IllegalArgumentException(action);
            }
            if (!args[0].equals("Cleanup")) inspect();
        } finally { client.shutdown(); }
    }
    static void prepare() throws Exception {
        if (Files.exists(FILE)) throw new IllegalStateException("A recorded fixture already exists; Inspect/Cleanup it first");
        String marker = "PHASE3_TEST_" + System.currentTimeMillis();
        String phone = "199" + marker.substring(marker.length() - 8);
        if (number("SELECT COUNT(*) FROM tb_user WHERE phone=?", phone) != 0) throw new IllegalStateException("Phone collision");
        fixture = new LinkedHashMap<>();
        fixture.put("marker", marker); fixture.put("baseline", fingerprints());
        fixture.put("phone", phone); fixture.put("voucherIds", new ArrayList<Long>());
        save();
        request("POST", "/user/code?phone=" + phone, null, null);
        String code = redis.sync().get("login:code:" + phone);
        String token = request("POST", "/user/login", map("phone", phone, "code", code), null).path("data").asText();
        fixture.put("token", token);
        fixture.put("userId", request("GET", "/user/me", null, token).path("data").path("id").asLong()); save();
        long shop = request("POST", "/shop", map("name", marker, "typeId", 1, "images", "", "address", "LOCAL TEST ONLY",
                "x", 120, "y", 30, "sold", 0, "comments", 0, "score", 0, "openHours", "00:00-23:59"), token).path("data").asLong();
        fixture.put("shopId", shop); save();
        long blog = request("POST", "/blog", map("shopId", shop, "title", marker, "images", "", "content", "Independent test note",
                "liked", 0, "comments", 0), token).path("data").asLong();
        fixture.put("blogId", blog); save();
        refreshDemo();
        System.out.println("FIXTURE_PREPARED independent user/shop/blog/3 seckill vouchers/2 regular vouchers; each seckill stock=3; no payment");
    }
    static void refreshDemo() throws Exception {
        if (Objects.equals(fixture.get("demoVersion"), 2)) return;
        String phone = (String) fixture.get("phone");
        request("POST", "/user/code?phone=" + phone, null, null);
        String code = redis.sync().get("login:code:" + phone);
        fixture.put("token", request("POST", "/user/login", map("phone", phone, "code", code), null).path("data").asText());
        fixture.putIfAbsent("displayVoucherIds", new ArrayList<Long>());
        fixture.putIfAbsent("ordinaryVoucherIds", new ArrayList<Long>());
        fixture.putIfAbsent("voucherTitles", new LinkedHashMap<String, String>());
        fixture.put("shopName", "邻享茶餐厅（体验店）");
        fixture.put("blogTitle", "邻享茶餐厅的优惠体验");
        save();
        execute("UPDATE tb_shop SET name=?,address=?,area=?,avg_price=?,open_hours=? WHERE id=? AND (name=? OR name=?)",
                fixture.get("shopName"), "本地体验店，不对应实际地址", "同城", 80, "10:00-22:00", fixture.get("shopId"), fixture.get("marker"), fixture.get("shopName"));
        redis.sync().del("cache:shop:" + fixture.get("shopId"), "cache:voucher:list:" + fixture.get("shopId"));
        if (fixture.containsKey("blogId")) execute("UPDATE tb_blog SET title=?,content=? WHERE id=? AND (title=? OR title=?)",
                fixture.get("blogTitle"), "体验店提供限时秒杀券和普通代金券，购买金额与可抵扣金额分别展示。", fixture.get("blogId"), fixture.get("marker"), fixture.get("blogTitle"));
        // Keep existing orders and the one-cent amounts they referred to. Only remove legacy test coupons from sale.
        for (Number id : ids()) if (!((List<Number>) fixture.get("displayVoucherIds")).contains(id))
            execute("UPDATE tb_voucher SET status=2 WHERE id=? AND shop_id=?", id, fixture.get("shopId"));
        addDemoVoucher("百元餐饮代金券", 5900, 10000, true);
        addDemoVoucher("双人餐饮代金券", 8900, 15000, true);
        addDemoVoucher("家庭聚餐代金券", 12900, 20000, true);
        addDemoVoucher("日常餐饮代金券", 8800, 10000, false);
        addDemoVoucher("聚餐优惠代金券", 16800, 20000, false);
        fixture.put("demoVersion", 2); save();
        System.out.println("FIXTURE_DEMO_READY Chinese shop/coupon names; existing order amounts preserved; no payment");
    }
    static void addDemoVoucher(String title, long payValue, long actualValue, boolean seckill) throws Exception {
        Map<String, String> titles = (Map<String, String>) fixture.get("voucherTitles");
        if (titles.containsValue(title)) return;
        Map<String, Object> data = map("shopId", fixture.get("shopId"), "title", title,
                "subTitle", "消费满" + actualValue / 100 + "元可使用，不找零、不可叠加",
                "rules", "全店餐饮可用；每次消费限用一张；不可与其他优惠叠加。",
                "type", seckill ? 1 : 0, "status", 1, "payValue", payValue, "actualValue", actualValue);
        if (seckill) {
            data.put("stock", 3); data.put("beginTime", LocalDateTime.now().minusHours(1).toString());
            data.put("endTime", LocalDateTime.now().plusDays(7).toString());
        }
        long id = request("POST", seckill ? "/voucher/seckill" : "/voucher", data, token()).path("data").asLong();
        if (seckill) { ids().add(id); ((List<Number>) fixture.get("displayVoucherIds")).add(id); }
        else ordinaryIds().add(id);
        titles.put(Long.toString(id), title); save();
    }
    static void verifyOwnership() throws Exception {
        String marker = (String) fixture.get("marker");
        if (marker == null || !marker.matches("PHASE3_TEST_[0-9]+")) throw new IllegalStateException("Invalid fixture marker");
        if (fixture.containsKey("userId") && number("SELECT COUNT(*) FROM tb_user WHERE id=? AND phone=?", fixture.get("userId"), fixture.get("phone")) != 1) throw new IllegalStateException("User ownership mismatch");
        if (fixture.containsKey("shopId") && number("SELECT COUNT(*) FROM tb_shop WHERE id=? AND (name=? OR name=?)", fixture.get("shopId"), marker, fixture.getOrDefault("shopName", marker)) != 1) throw new IllegalStateException("Shop ownership mismatch");
        List<Number> all = new ArrayList<>(ids()); all.addAll(ordinaryIds());
        Map<String, String> titles = (Map<String, String>) fixture.getOrDefault("voucherTitles", Collections.emptyMap());
        for (Number id : all) {
            String title = titles.get(id.toString());
            long matches = title == null
                    ? number("SELECT COUNT(*) FROM tb_voucher WHERE id=? AND shop_id=? AND title LIKE ?", id, fixture.get("shopId"), marker + "%")
                    : number("SELECT COUNT(*) FROM tb_voucher WHERE id=? AND shop_id=? AND title=?", id, fixture.get("shopId"), title);
            if (matches != 1) throw new IllegalStateException("Voucher ownership mismatch");
        }
    }
    static void reset(boolean cleanup) throws Exception {
        // A committed MySQL row alone is insufficient: an uncommitted Kafka offset could replay after reset.
        Properties kafkaProperties = new Properties(); kafkaProperties.put("bootstrap.servers", setting("spring.kafka.bootstrap-servers"));
        try (AdminClient admin = AdminClient.create(kafkaProperties)) {
            String topic = setting("hmdp.seckill.topic"); if (topic.isEmpty()) topic = "seckill-voucher-order";
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (org.apache.kafka.common.TopicPartitionInfo partition : admin.describeTopics(Collections.singleton(topic)).all().get(5, TimeUnit.SECONDS).get(topic).partitions())
                latest.put(new TopicPartition(topic, partition.partition()), OffsetSpec.latest());
            Map<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo> ends = admin.listOffsets(latest).all().get(5, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(setting("spring.kafka.consumer.group-id")).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            for (TopicPartition partition : ends.keySet()) if (ends.get(partition).offset() > 0 && (committed.get(partition) == null || committed.get(partition).offset() < ends.get(partition).offset()))
                throw new IllegalStateException("Kafka still has uncommitted records; wait for recovery before Reset/Cleanup");
            // Delete only a prefix proven to consist entirely of this fixture's messages.
            // If any original message exists, preserve the topic and its offsets instead.
            Properties readerProperties = new Properties(); readerProperties.putAll(kafkaProperties);
            readerProperties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            readerProperties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            readerProperties.put("enable.auto.commit", "false");
            boolean allOwned = true; int seen = 0;
            try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(readerProperties)) {
                reader.assign(ends.keySet()); reader.seekToBeginning(ends.keySet());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (true) {
                    boolean finished = true;
                    for (TopicPartition partition : ends.keySet()) if (reader.position(partition) < ends.get(partition).offset()) finished = false;
                    if (finished) break;
                    if (System.nanoTime() > deadline) throw new IllegalStateException("Could not inspect Kafka prefix ownership");
                    for (ConsumerRecord<String, String> record : reader.poll(Duration.ofMillis(250))) {
                        if (record.offset() >= ends.get(new TopicPartition(record.topic(), record.partition())).offset()) continue;
                        seen++; JsonNode value = JSON.readTree(record.value());
                        long voucherId = value.path("voucherId").asLong();
                        if (value.path("userId").asLong() != ((Number) fixture.get("userId")).longValue()
                                || ids().stream().noneMatch(id -> id.longValue() == voucherId)) allOwned = false;
                    }
                }
            }
            if (allOwned && seen > 0) {
                Map<TopicPartition, RecordsToDelete> owned = new HashMap<>();
                for (TopicPartition partition : ends.keySet()) owned.put(partition, RecordsToDelete.beforeOffset(ends.get(partition).offset()));
                admin.deleteRecords(owned).all().get(5, TimeUnit.SECONDS);
                System.out.println("KAFKA_OWN_PREFIX_CLEANUP_PASS messages=" + seen + " originalMessagesDeleted=0");
            }
        }
        String outbox = setting("hmdp.seckill.outbox"); if (outbox.isEmpty()) outbox = "seckill:outbox";
        for (io.lettuce.core.StreamMessage<String, String> entry : redis.sync().xrange(outbox, io.lettuce.core.Range.unbounded())) {
            if (ids().stream().anyMatch(id -> id.toString().equals(entry.getBody().get("voucherId")))) throw new IllegalStateException("Owned order is still awaiting Kafka; recover it before reset");
        }
        for (Number voucher : ids()) {
            long count = number("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", voucher);
            long stock = number("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", voucher);
            if (!Long.toString(stock).equals(redis.sync().get("seckill:stock:" + voucher)) || stock + count != 3) throw new IllegalStateException("In-flight order or inventory drift; refusing fixture reset");
            try (PreparedStatement statement = db.prepareStatement("SELECT id FROM tb_voucher_order WHERE voucher_id=?")) {
                statement.setObject(1, voucher);
                try (ResultSet result = statement.executeQuery()) { while (result.next()) redis.sync().del("seckill:reservation:" + result.getLong(1)); }
            }
            execute("DELETE FROM tb_voucher_order WHERE voucher_id=?", voucher);
            redis.sync().del("seckill:order:" + voucher);
            if (cleanup) {
                execute("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", voucher);
                execute("DELETE FROM tb_voucher WHERE id=?", voucher);
                redis.sync().del("seckill:stock:" + voucher);
            } else { execute("UPDATE tb_seckill_voucher SET stock=3 WHERE voucher_id=?", voucher); redis.sync().set("seckill:stock:" + voucher, "3"); }
        }
        for (Number id : ordinaryIds()) {
            execute("DELETE FROM tb_voucher_order WHERE voucher_id=?", id);
            if (cleanup) execute("DELETE FROM tb_voucher WHERE id=? AND shop_id=? AND type=0", id, fixture.get("shopId"));
        }
        if (cleanup) {
            if (fixture.containsKey("blogId")) { execute("DELETE FROM tb_blog WHERE id=? AND (title=? OR title=?)", fixture.get("blogId"), fixture.get("marker"), fixture.getOrDefault("blogTitle", fixture.get("marker"))); redis.sync().del("blog:liked:" + fixture.get("blogId")); }
            if (fixture.containsKey("shopId")) { execute("DELETE FROM tb_shop WHERE id=? AND (name=? OR name=?)", fixture.get("shopId"), fixture.get("marker"), fixture.getOrDefault("shopName", fixture.get("marker"))); redis.sync().del("cache:shop:" + fixture.get("shopId"), "cache:voucher:list:" + fixture.get("shopId")); }
            if (fixture.containsKey("userId")) execute("DELETE FROM tb_user WHERE id=? AND phone=?", fixture.get("userId"), fixture.get("phone"));
            // Prepare, Demo and a browser login may each create a token. Remove only this fixture user's sessions.
            io.lettuce.core.ScanCursor cursor = io.lettuce.core.ScanCursor.INITIAL;
            do {
                io.lettuce.core.KeyScanCursor<String> keys = redis.sync().scan(cursor, io.lettuce.core.ScanArgs.Builder.matches("login:token:*").limit(100));
                for (String key : keys.getKeys()) if (Objects.toString(fixture.get("userId")).equals(redis.sync().hget(key, "id"))) redis.sync().del(key);
                cursor = keys;
            } while (!cursor.isFinished());
            redis.sync().del("login:token:" + fixture.get("token"), "login:code:" + fixture.get("phone"));
            Object actual = fingerprints();
            Map<String, Object> proof = map("before", fixture.get("baseline"), "after", actual, "equal", fixture.get("baseline").equals(actual));
            Path evidence = Paths.get("docs/development/evidence/phase3/fixture-cleanup-" + fixture.get("marker") + ".json");
            Files.createDirectories(evidence.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), proof);
            Files.delete(FILE);
            if (!(Boolean) proof.get("equal")) throw new IllegalStateException("Original row fingerprint changed; inspect cleanup proof");
            System.out.println("FIXTURE_CLEANUP_PASS original table SHA256 fingerprints restored");
        } else System.out.println("FIXTURE_RESET_PASS own voucher inventories restored to 3; original data untouched");
    }
    static void inspect() throws Exception {
        List<Object> inventory = new ArrayList<>();
        List<Object> ordinaryOrders = new ArrayList<>();
        for (Number id : ids()) inventory.add(map("voucherId", id, "displayed", displayIds().contains(id), "redisStock", redis.sync().get("seckill:stock:" + id),
                "mysqlStock", number("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", id), "orders", number("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", id)));
        for (Number id : ordinaryIds()) ordinaryOrders.add(map("voucherId", id,
                "orders", number("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", id),
                "unpaidOrders", number("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=? AND status=1", id)));
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(map("marker", fixture.get("marker"), "phone", fixture.get("phone"),
                "shopId", fixture.get("shopId"), "shopName", fixture.getOrDefault("shopName", fixture.get("marker")), "blogId", fixture.get("blogId"), "userId", fixture.get("userId"), "inventory", inventory,
                "displayVoucherIds", displayIds(), "ordinaryVoucherIds", ordinaryIds(), "ordinaryOrders", ordinaryOrders,
                "redisAof", redis.sync().configGet("appendonly"), "redisFsync", redis.sync().configGet("appendfsync"))));
    }
    static Map<String, String> fingerprints() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (String table : TABLES) {
            List<Object> rows = new ArrayList<>();
            try (Statement statement = db.createStatement(); ResultSet results = statement.executeQuery("SELECT * FROM " + table + " ORDER BY " + (table.equals("tb_seckill_voucher") ? "voucher_id" : "id"))) {
                while (results.next()) { List<String> row = new ArrayList<>(); for (int i = 1; i <= results.getMetaData().getColumnCount(); i++) row.add(Objects.toString(results.getObject(i), "<NULL>")); rows.add(row); }
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(rows));
            StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format("%02x", b)); values.put(table, hex.toString());
        }
        return values;
    }
    static long number(String sql, Object... params) throws Exception { try (PreparedStatement statement = db.prepareStatement(sql)) { bind(statement, params); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); } } }
    static void execute(String sql, Object... params) throws Exception { try (PreparedStatement statement = db.prepareStatement(sql)) { bind(statement, params); statement.executeUpdate(); } }
    static void bind(PreparedStatement statement, Object[] params) throws Exception { for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]); }
    static List<Number> ids() { return (List<Number>) fixture.get("voucherIds"); }
    static List<Number> ordinaryIds() { return (List<Number>) fixture.getOrDefault("ordinaryVoucherIds", Collections.emptyList()); }
    static List<Number> displayIds() { return (List<Number>) fixture.getOrDefault("displayVoucherIds", fixture.get("voucherIds")); }
    static String token() { return (String) fixture.get("token"); }
    static void save() throws Exception { Files.createDirectories(FILE.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(FILE.toFile(), fixture); }
    static Map<String, Object> map(Object... entries) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]); return result; }
    static JsonNode request(String method, String path, Object data, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(System.getProperty("neighborlife.base-url", "http://127.0.0.1:8081") + path)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json");
        if (token != null) request.header("authorization", token);
        request.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(data)));
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode result = JSON.readTree(response.body());
        if (response.statusCode() != 200 || !result.path("success").asBoolean()) throw new IllegalStateException("Request failed: " + path + " " + result);
        return result;
    }
}
