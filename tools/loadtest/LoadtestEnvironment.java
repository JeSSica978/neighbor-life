package loadtest;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Separate command-line tools; never starts Spring, a consumer, or the Publisher. */
public final class LoadtestEnvironment implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final String[] TABLES = {"tb_shop", "tb_user", "tb_blog", "tb_blog_comments", "tb_follow",
            "tb_shop_type", "tb_user_info", "tb_voucher", "tb_seckill_voucher", "tb_voucher_order"};
    public final String runId, topic, group, outbox, mysqlUrl, baseUrl;
    public final int mysqlPort, redisPort, kafkaPort, serverPort;
    public final Path directory, manifestFile;
    public Connection db;
    public RedisClient redisClient;
    public StatefulRedisConnection<String, String> redis;
    public AdminClient admin;
    public static class Guard extends RuntimeException { public Guard(String message) { super(message); } }
    public static void require(boolean condition, String message) { if (!condition) throw new Guard(message); }
    public static String env(String name, String fallback) {
        String value = System.getenv(name); return value == null || value.isEmpty() ? fallback : value;
    }
    public static Map<String, String> arguments(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            require(args[i].startsWith("--"), "Expected --option");
            String key = args[i].substring(2);
            require(!result.containsKey(key), "Duplicate option");
            if (key.equals("execute")) result.put(key, "true");
            else { require(i + 1 < args.length && !args[i + 1].startsWith("--"), "Missing option value"); result.put(key, args[++i]); }
        }
        return result;
    }
    public LoadtestEnvironment(Map<String, String> args) {
        runId = args.getOrDefault("run-id", "");
        require(runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,47}"), "Explicit --run-id (3-48 safe characters) required");
        require(env("LT_RUN_ID", runId).equals(runId), "LT_RUN_ID differs from --run-id");
        for (String key : System.getenv().keySet()) {
            require(!key.startsWith("SPRING_DATASOURCE_") && !key.startsWith("SPRING_REDIS_")
                    && !key.startsWith("SPRING_KAFKA_") && !key.startsWith("HMDP_SECKILL_")
                    && !key.equals("SPRING_APPLICATION_JSON") && !key.equals("SPRING_CONFIG_LOCATION")
                    && !key.equals("SERVER_PORT") && !key.equals("SERVER_ADDRESS"), "Remove conflicting Spring environment overrides before using isolated tools");
        }
        for (String key : new String[]{"LT_MYSQL_HOST", "LT_REDIS_HOST", "LT_KAFKA_HOST"})
            require(env(key, "127.0.0.1").equals("127.0.0.1"), "Tools require loopback hosts");
        mysqlPort = port("LT_MYSQL_PORT", 3307, 3306); redisPort = port("LT_REDIS_PORT", 6380, 6379);
        kafkaPort = port("LT_KAFKA_PORT", 9093, 9092); serverPort = port("LT_SERVER_PORT", 8082, 8081);
        require(new HashSet<>(Arrays.asList(mysqlPort, redisPort, kafkaPort, serverPort)).size() == 4, "Isolated ports must differ");
        mysqlUrl = env("LT_MYSQL_URL", "jdbc:mysql://127.0.0.1:" + mysqlPort + "/hmdp_loadtest?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        URI uri = URI.create(mysqlUrl.substring(5));
        require(mysqlUrl.startsWith("jdbc:mysql://") && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == mysqlPort
                && "/hmdp_loadtest".equals(uri.getPath()) && uri.getUserInfo() == null
                && !Objects.toString(uri.getQuery(), "").matches("(?i).*(password|user|socketFactory)=.*"), "MySQL URL must name only the isolated database/port, without credentials");
        baseUrl = "http://127.0.0.1:" + serverPort;
        topic = "loadtest-seckill-" + runId; group = "loadtest-orders-" + runId; outbox = "loadtest:outbox:" + runId;
        Path privateRoot = Paths.get("target", "loadtest-private").toAbsolutePath().normalize();
        directory = privateRoot.resolve(runId).normalize();
        require(directory.startsWith(privateRoot), "Private path escaped target");
        manifestFile = directory.resolve("manifest.json");
    }
    private int port(String name, int fallback, int forbidden) {
        int value = Integer.parseInt(env(name, String.valueOf(fallback)));
        require(value > 1024 && value <= 65535 && value != forbidden, "Refusing a default business port"); return value;
    }
    public ObjectNode description() {
        ObjectNode node = JSON.createObjectNode();
        node.put("baseUrl", baseUrl); node.put("mysqlHost", "127.0.0.1"); node.put("mysqlPort", mysqlPort);
        node.put("database", "hmdp_loadtest"); node.put("redisHost", "127.0.0.1"); node.put("redisPort", redisPort);
        node.put("redisDatabase", 0); node.put("kafkaBootstrap", "127.0.0.1:" + kafkaPort);
        node.put("topic", topic); node.put("consumerGroup", group); node.put("outbox", outbox); return node;
    }
    public void connect(boolean writer) throws Exception {
        String password = env(writer ? "LT_MYSQL_APP_PASSWORD" : "LT_MYSQL_OBSERVER_PASSWORD", "");
        require(!password.isEmpty(), "Set the isolated database account password in the environment");
        db = DriverManager.getConnection(mysqlUrl, writer ? "lt_app" : "lt_observer", password);
        db.setReadOnly(!writer); db.setAutoCommit(true);
        require(scalar("SELECT COUNT(*) FROM lt_environment WHERE id=1 AND marker='neighborlife-loadtest-v1'") == 1,
                "Dedicated schema marker missing; refusing data access");
        redisClient = RedisClient.create(RedisURI.Builder.redis("127.0.0.1", redisPort).withDatabase(0).withTimeout(Duration.ofSeconds(5)).build());
        redis = redisClient.connect();
        Properties config = new Properties(); config.put("bootstrap.servers", "127.0.0.1:" + kafkaPort);
        config.put("request.timeout.ms", "5000"); config.put("default.api.timeout.ms", "10000");
        admin = AdminClient.create(config);
    }
    public PreparedStatement statement(String sql, Object... values) throws SQLException {
        PreparedStatement ps = db.prepareStatement(sql); ps.setQueryTimeout(5);
        for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]); return ps;
    }
    public long scalar(String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = statement(sql, values); ResultSet rs = ps.executeQuery()) {
            require(rs.next(), "SQL expected one result"); return rs.getLong(1);
        }
    }
    public int execute(String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = statement(sql, values)) { return ps.executeUpdate(); }
    }
    public static String marks(int count) { return String.join(",", Collections.nCopies(count, "?")); }
    public static Set<Long> ids(JsonNode array, String field) {
        Set<Long> ids = new LinkedHashSet<>(); for (JsonNode node : array) ids.add(node.path(field).asLong()); return ids;
    }
    public static void safeId(long id, boolean shop) {
        require(id >= 1000000 && (shop ? id != 18 : id != 26 && id != 27 && id != 28), "Refusing protected/legacy data ID");
    }
    public ObjectNode readManifest() throws Exception {
        require(Files.isRegularFile(manifestFile) && !Files.isSymbolicLink(manifestFile), "Owned manifest missing");
        ObjectNode node = (ObjectNode) JSON.readTree(manifestFile.toFile());
        require(runId.equals(node.path("runId").asText()) && description().equals(node.path("environment")), "Manifest environment/ownership mismatch");
        return node;
    }
    public void save(ObjectNode node) throws Exception {
        Files.createDirectories(directory);
        Path next = directory.resolve("manifest.next.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(next.toFile(), node);
        try { Files.move(next, manifestFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(next, manifestFile, StandardCopyOption.REPLACE_EXISTING); }
    }
    public JsonNode request(String method, String path, JsonNode body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("authorization", token); // Raw token, deliberately no Bearer.
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200, "Business preparation HTTP failed; response body suppressed");
        JsonNode json = JSON.readTree(response.body());
        require(json.path("success").asBoolean(), "Business preparation rejected; response body suppressed"); return json.path("data");
    }
    public ObjectNode kafka() throws Exception {
        TopicDescription description = admin.describeTopics(Collections.singleton(topic)).all().get(10, TimeUnit.SECONDS).get(topic);
        require(description.partitions().size() == 1 && description.partitions().get(0).replicas().size() == 1, "Expected one partition/replica");
        TopicPartition tp = new TopicPartition(topic, 0);
        Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        long end = admin.listOffsets(Collections.singletonMap(tp, OffsetSpec.latest())).all().get(10, TimeUnit.SECONDS).get(tp).offset();
        OffsetAndMetadata offset = committed.get(tp);
        ObjectNode node = JSON.createObjectNode(); node.put("CURRENT_OFFSET", offset == null ? -1 : offset.offset());
        node.put("LOG_END_OFFSET", end); node.put("LAG", offset == null ? (end == 0 ? 0 : -1) : end - offset.offset());
        ConsumerGroupDescription groupState = admin.describeConsumerGroups(Collections.singleton(group)).all().get(10, TimeUnit.SECONDS).get(group);
        node.put("groupState", groupState.state().toString()); node.put("activeMembers", groupState.members().size()); return node;
    }
    public ObjectNode outbox() {
        ObjectNode node = JSON.createObjectNode(); node.put("xlen", redis.sync().xlen(outbox));
        List<StreamMessage<String, String>> first = redis.sync().xrange(outbox, Range.unbounded(), Limit.from(1));
        if (first.isEmpty()) node.put("oldestAgeMs", 0);
        else node.put("oldestAgeMs", Math.max(0, System.currentTimeMillis() - Long.parseLong(first.get(0).getId().split("-")[0])));
        return node;
    }
    public List<String> reservations(Set<Long> voucherIds, boolean pendingOnly) {
        List<String> result = new ArrayList<>(); ScanCursor cursor = ScanCursor.INITIAL;
        do {
            KeyScanCursor<String> batch = redis.sync().scan(cursor, ScanArgs.Builder.matches("seckill:reservation:*").limit(500));
            for (String key : batch.getKeys()) {
                Map<String, String> values = redis.sync().hgetall(key);
                if (voucherIds.contains(Long.valueOf(values.getOrDefault("voucherId", "-1")))
                        && (!pendingOnly || !"CREATED".equals(values.get("state")))) result.add(key);
            }
            cursor = batch;
        } while (!cursor.isFinished()); return result;
    }
    public ObjectNode consistency(ObjectNode manifest) throws Exception {
        ObjectNode result = JSON.createObjectNode(); result.put("sampledAt", Instant.now().toString());
        ArrayNode coupons = result.putArray("vouchers"); Set<Long> ownedUsers = ids(manifest.path("users"), "id");
        boolean pass = true;
        for (JsonNode coupon : manifest.path("vouchers")) {
            long id = coupon.path("id").asLong(); safeId(id, false);
            long stock = scalar("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", id);
            String redisStock = redis.sync().get("seckill:stock:" + id);
            Set<String> purchased = redis.sync().smembers("seckill:order:" + id);
            Set<String> sqlUsers = new HashSet<>(); Set<Long> orderIds = new HashSet<>();
            try (PreparedStatement ps = statement("SELECT id,user_id FROM tb_voucher_order WHERE voucher_id=?", id); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { require(ownedUsers.contains(rs.getLong(2)), "Foreign user order found on owned voucher"); orderIds.add(rs.getLong(1)); sqlUsers.add(rs.getString(2)); }
            }
            List<String> pending = reservations(Collections.singleton(id), true);
            boolean invariant = redisStock != null && stock >= 0 && stock == Long.parseLong(redisStock)
                    && stock + orderIds.size() == coupon.path("initialStock").asLong()
                    && orderIds.size() == sqlUsers.size() && purchased.equals(sqlUsers) && pending.isEmpty();
            ObjectNode row = coupons.addObject(); row.put("voucherId", id); row.put("mysqlStock", stock);
            row.put("redisStock", redisStock); row.put("orderCount", orderIds.size()); row.put("qualificationUsers", purchased.size());
            row.put("unfinishedReservations", pending.size()); row.put("drainedInvariant", invariant); pass &= invariant;
        }
        result.set("outbox", outbox()); result.set("kafka", kafka());
        pass &= result.path("outbox").path("xlen").asLong() == 0 && result.path("kafka").path("LAG").asLong() == 0;
        result.put("drainedInvariant", pass); return result;
    }
    public void verifyOwnership(ObjectNode manifest) throws Exception {
        long shop = manifest.path("shopId").asLong(); safeId(shop, true);
        long type = manifest.path("typeId").asLong(); safeId(type, true);
        require(scalar("SELECT COUNT(*) FROM tb_shop_type WHERE id=? AND name=?", type, manifest.path("typeName").asText()) == 1
                && scalar("SELECT COUNT(*) FROM tb_shop WHERE type_id=?", type) == 1, "Category ownership mismatch");
        require(scalar("SELECT COUNT(*) FROM tb_shop WHERE id=? AND name=?", shop, manifest.path("marker").asText()) == 1, "Shop ownership mismatch");
        Set<Long> vouchers = ids(manifest.path("vouchers"), "id");
        for (JsonNode coupon : manifest.path("vouchers")) {
            long id = coupon.path("id").asLong(); safeId(id, false);
            require(scalar("SELECT COUNT(*) FROM tb_voucher WHERE id=? AND shop_id=? AND title=? AND type=1", id, shop, coupon.path("title").asText()) == 1, "Coupon ownership mismatch");
        }
        require(scalar("SELECT COUNT(*) FROM tb_voucher WHERE shop_id=?", shop) == vouchers.size(), "Unrecorded coupon belongs to shop");
        require(scalar("SELECT COUNT(*) FROM tb_blog WHERE shop_id=?", shop) == 0, "Foreign blog references owned shop");
        for (JsonNode user : manifest.path("users")) {
            long id = user.path("id").asLong(); require(id >= 1000000, "Refusing legacy user");
            require(scalar("SELECT COUNT(*) FROM tb_user WHERE id=? AND phone=?", id, user.path("phone").asText()) == 1, "User ownership mismatch");
            try (PreparedStatement ps = statement("SELECT voucher_id FROM tb_voucher_order WHERE user_id=?", id); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) require(vouchers.contains(rs.getLong(1)), "Owned user has foreign voucher orders");
            }
            require(scalar("SELECT COUNT(*) FROM tb_blog WHERE user_id=?", id) == 0 && scalar("SELECT COUNT(*) FROM tb_follow WHERE user_id=? OR follow_user_id=?", id, id) == 0
                    && scalar("SELECT COUNT(*) FROM tb_user_info WHERE user_id=?", id) == 0 && scalar("SELECT COUNT(*) FROM tb_blog_comments WHERE user_id=?", id) == 0, "User has unrelated dependent data");
        }
    }
    public ObjectNode fingerprints(ObjectNode owned) throws Exception {
        ObjectNode hashes = JSON.createObjectNode();
        Set<Long> shops = Collections.singleton(owned.path("shopId").asLong(-1));
        Set<Long> types = Collections.singleton(owned.path("typeId").asLong(-1));
        Set<Long> users = ids(owned.path("users"), "id"), vouchers = ids(owned.path("vouchers"), "id");
        for (String table : TABLES) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String key = table.equals("tb_seckill_voucher") ? "voucher_id" : table.equals("tb_user_info") ? "user_id" : "id";
            try (PreparedStatement ps = statement("SELECT * FROM " + table + " ORDER BY " + key); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(key);
                    if (table.equals("tb_shop") && shops.contains(id) || table.equals("tb_shop_type") && types.contains(id) || table.equals("tb_user") && users.contains(id)
                            || (table.equals("tb_voucher") || table.equals("tb_seckill_voucher")) && vouchers.contains(id)
                            || table.equals("tb_voucher_order") && vouchers.contains(rs.getLong("voucher_id"))) continue;
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        byte[] bytes = Objects.toString(rs.getObject(i), "<NULL>").getBytes(StandardCharsets.UTF_8);
                        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
                    }
                }
            }
            StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255)); hashes.put(table, hex.toString());
        }
        return hashes;
    }
    public void requireBackendStopped() throws Exception {
        boolean listening;
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", serverPort), 500); listening = true; }
        catch (java.io.IOException e) { listening = false; }
        require(!listening, "Stop only the dedicated loadtest backend before reset/cleanup (tool never stops it)");
        ObjectNode kafka = kafka();
        require(kafka.path("activeMembers").asInt() == 0 && "Empty".equalsIgnoreCase(kafka.path("groupState").asText())
                && kafka.path("LAG").asLong() == 0 && outbox().path("xlen").asLong() == 0, "Consumer/outbox not completely drained and inactive");
    }
    @Override public void close() {
        if (admin != null) admin.close(Duration.ofSeconds(2)); if (redis != null) redis.close(); if (redisClient != null) redisClient.shutdown();
        if (db != null) try { db.close(); } catch (SQLException ignored) { }
    }
    public static void reportError(Exception e) {
        System.err.println(e instanceof Guard ? "GUARD: " + e.getMessage() : "FAILED type=" + e.getClass().getSimpleName() + "; credentials/HTTP bodies suppressed; inspect private manifest state");
    }
}
