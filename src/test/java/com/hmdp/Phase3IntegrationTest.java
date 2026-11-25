package com.hmdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.SeckillConstants;
import com.hmdp.messaging.SeckillOrderPublisher;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.util.AopTestUtils;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Explicitly selected local integration suite. Never run the historical load/data mutation tests. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Phase3IntegrationTest {
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static final String TOPIC = "seckill-voucher-order-phase3-" + RUN;
    private static final String GROUP = "neighborlife-phase3-" + RUN;
    private static final String OUTBOX = "seckill:outbox:phase3:" + RUN;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("hmdp.seckill.topic", () -> TOPIC);
        registry.add("hmdp.seckill.outbox", () -> OUTBOX);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
        registry.add("hmdp.seckill.publish-delay-ms", () -> "200");
    }
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired SeckillOrderPublisher publisher;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduling;
    @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers;
    @Autowired IVoucherOrderService orderService;
    @Autowired VoucherOrderMapper orderMapper;
    private final List<Long> vouchers = new ArrayList<>();
    private final List<Long> users = new ArrayList<>();
    private final List<String> phones = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private final Set<Long> orders = new LinkedHashSet<>();
    private final List<Map<String, Object>> evidence = new ArrayList<>();
    private long shopId;
    private long blogId;
    private String trigger;
    private AdminClient admin;

    @Test void verifyNormalVoucherOrders() throws Exception {
        Map<String, Integer> before = rowCounts();
        Map<String, String> fingerprintsBefore = rowFingerprints();
        try {
            // Only inspect the deployed constraint; this test does not migrate business tables.
            ensureUniqueIndex();
            login(0); login(1);
            shopId = request("POST", "/shop", map("name", "PHASE3_TEST_" + RUN, "typeId", 1,
                    "images", "", "address", "LOCAL TEST ONLY", "x", 120, "y", 30, "sold", 0,
                    "comments", 0, "score", 0, "openHours", "00:00-23:59"), tokens.get(0)).path("data").asLong();
            long regular = ordinaryVoucher();
            assertEquals(401, http.postForEntity("/voucher-order/normal/" + regular, null, String.class).getStatusCodeValue());
            assertFalse(request("POST", "/voucher-order/normal/0", null, tokens.get(0), false).path("success").asBoolean());
            assertFalse(request("POST", "/voucher-order/normal/9223372036854775807", null, tokens.get(0), false).path("success").asBoolean());
            long seckill = voucher(2);
            assertFalse(request("POST", "/voucher-order/normal/" + seckill, null, tokens.get(0), false).path("success").asBoolean());
            assertStock(seckill, 2, 2);
            jdbc.update("UPDATE tb_voucher SET status=2 WHERE id=?", regular);
            assertFalse(request("POST", "/voucher-order/normal/" + regular, null, tokens.get(0), false).path("success").asBoolean());
            jdbc.update("UPDATE tb_voucher SET status=1,pay_value=0 WHERE id=?", regular);
            assertFalse(request("POST", "/voucher-order/normal/" + regular, null, tokens.get(0), false).path("success").asBoolean());
            jdbc.update("UPDATE tb_voucher SET pay_value=8800 WHERE id=?", regular);
            record("NORMAL_VALIDATION_PASS", "login/type/status/id/amount validated; no rejected order inserted", null);
            assertEquals(0, countOrders(regular));
            String firstRequest = UUID.randomUUID().toString();
            String first = request("POST", "/voucher-order/normal/" + regular, null, tokens.get(0), true, firstRequest).path("data").asText();
            assertTrue(first.matches("[0-9]+"));
            assertEquals(first, request("POST", "/voucher-order/normal/" + regular, null, tokens.get(0), true, firstRequest).path("data").asText());
            assertEquals(1, countOrders(regular));
            assertEquals(1, jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE id=?", Integer.class, first));
            assertNull(jdbc.queryForObject("SELECT pay_time FROM tb_voucher_order WHERE id=?", java.sql.Timestamp.class, first));
            JsonNode status = request("GET", "/voucher-order/" + first, null, tokens.get(0)).path("data");
            assertEquals(first, status.path("id").asText());
            assertEquals("CREATED", status.path("state").asText());
            assertEquals(1, status.path("status").asInt());
            assertFalse(request("GET", "/voucher-order/" + first, null, tokens.get(1), false).path("success").asBoolean());
            record("NORMAL_ORDER_PASS", "real committed unpaid MySQL order; retry returns same string ID; owner-only query", map("orderId", first, "voucherId", regular, "status", 1));
            String second = request("POST", "/voucher-order/normal/" + regular, null, tokens.get(0), true, UUID.randomUUID().toString()).path("data").asText();
            assertNotEquals(first, second); assertEquals(2, countOrders(regular));
            assertFalse(request("POST", "/voucher-order/normal/" + seckill, null, tokens.get(0), false, firstRequest).path("success").asBoolean());
            assertFalse(request("POST", "/voucher-order/normal/" + regular, null, tokens.get(0), false, "bad").path("success").asBoolean());
            ExecutorService workers = Executors.newFixedThreadPool(8);
            try {
                CountDownLatch start = new CountDownLatch(1);
                String concurrentRequest = UUID.randomUUID().toString();
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) results.add(workers.submit(() -> {
                    start.await();
                    return request("POST", "/voucher-order/normal/" + regular, null, tokens.get(1), true, concurrentRequest).path("data").asText();
                }));
                start.countDown();
                Set<String> returned = new HashSet<>();
                for (Future<String> result : results) returned.add(result.get(15, TimeUnit.SECONDS));
                assertEquals(1, returned.size());
                assertEquals(3, countOrders(regular));
                record("NORMAL_CONCURRENT_RETRY_PASS", "8 simultaneous requests create 1 order for second user", map("orderIds", returned));
            } finally { workers.shutdownNow(); }
            long recovery = ordinaryVoucher();
            Object serviceTarget = AopTestUtils.getTargetObject(orderService);
            Object originalMapper = ReflectionTestUtils.getField(serviceTarget, "baseMapper");
            VoucherOrderMapper failingMapper = mock(VoucherOrderMapper.class, delegatesTo(orderMapper));
            doAnswer(call -> {
                VoucherOrder pending = call.getArgument(0);
                pending.setId(Long.valueOf(first)); // Real duplicate-PK INSERT failure, without database SUPER privileges.
                return orderMapper.insertNormal(pending, call.getArgument(1));
            }).when(failingMapper).insertNormal(any(VoucherOrder.class), anyString());
            try {
                ReflectionTestUtils.setField(serviceTarget, "baseMapper", failingMapper);
                assertFalse(request("POST", "/voucher-order/normal/" + recovery, null, tokens.get(0), false).path("success").asBoolean());
            } finally { ReflectionTestUtils.setField(serviceTarget, "baseMapper", originalMapper); }
            assertEquals(0, countOrders(recovery));
            request("POST", "/voucher-order/normal/" + recovery, null, tokens.get(0));
            assertEquals(1, countOrders(recovery));
            assertStock(seckill, 2, 2);
            assertNull(redis.opsForValue().get("seckill:stock:" + regular));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(SeckillConstants.RESERVATION + first)));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, regular));
            assertTrue(redis.opsForStream().size(OUTBOX) == null || redis.opsForStream().size(OUTBOX) == 0);
            record("NORMAL_INSERT_RECOVERY_PASS", "failed insert returns success=false, creates 0 orders; retry succeeds; seckill inventory untouched", null);
        } finally {
            if (trigger != null) jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
            MessageListenerContainer listener = registry.getListenerContainer("seckillVoucherOrders");
            if (listener != null) listener.stop();
            cleanup();
            Map<String, Integer> after = rowCounts();
            Map<String, String> fingerprintsAfter = rowFingerprints();
            record("NORMAL_DATA_CLEANUP", "original rows restored", map("before", before, "after", after,
                    "fingerprintsBefore", fingerprintsBefore, "fingerprintsAfter", fingerprintsAfter));
            Path path = Paths.get(System.getProperty("phase3.normal.evidence", "docs/development/evidence/ordinary-vouchers/integration-tests.json"));
            Files.createDirectories(path.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evidence);
            try (AdminClient ownAdmin = AdminClient.create(Collections.singletonMap("bootstrap.servers", bootstrapServers))) {
                ownAdmin.deleteTopics(Collections.singleton(TOPIC)).all().get(10, TimeUnit.SECONDS);
            }
            assertEquals(before, after);
            assertEquals(fingerprintsBefore, fingerprintsAfter);
        }
    }

    private long ordinaryVoucher() throws Exception {
        long id = request("POST", "/voucher", map("shopId", shopId, "title", "PHASE3_TEST_" + RUN,
                "type", 0, "status", 1, "payValue", 8800, "actualValue", 10000), tokens.get(0)).path("data").asLong();
        vouchers.add(id);
        return id;
    }

    private Map<String, String> rowFingerprints() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (String table : rowCounts().keySet()) {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(
                    jdbc.queryForList("SELECT * FROM " + table + " ORDER BY " + (table.equals("tb_seckill_voucher") ? "voucher_id" : "id"))));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            values.put(table, hex.toString());
        }
        return values;
    }

    @Test void verifyRealOrderChainAndPersistence() throws Exception {
        Map<String, Integer> before = rowCounts();
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        admin = AdminClient.create(properties);
        try {
            try { admin.createTopics(Collections.singleton(new NewTopic(TOPIC, 1, (short) 1))).all().get(15, TimeUnit.SECONDS); }
            catch (ExecutionException e) { if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) throw e; }
            ensureUniqueIndex();
            for (int i = 0; i < 3; i++) login(i);
            assertEquals(401, http.getForEntity("/user/me", String.class).getStatusCodeValue());
            assertEquals(users.get(0).longValue(), request("GET", "/user/me", null, tokens.get(0)).path("data").path("id").asLong());
            record("LOGIN_PASS", "MySQL test users and Redis token hashes read back", null);

            shopId = request("POST", "/shop", map("name", "PHASE3_TEST_" + RUN, "typeId", 1,
                    "images", "", "address", "LOCAL TEST ONLY", "x", 120.0, "y", 30.0, "sold", 0,
                    "comments", 0, "score", 0, "openHours", "00:00-23:59"), tokens.get(0)).path("data").asLong();
            assertEquals("103茶餐厅", request("GET", "/shop/1", null, null).path("data").path("name").asText());
            assertEquals(10, request("GET", "/shop-type/list", null, null).path("data").size());
            assertTrue(request("GET", "/shop/of/name?current=1", null, null).path("data").size() > 0);
            assertEquals(1, request("GET", "/shop/of/name?name=PHASE3_TEST_" + RUN, null, null).path("data").size());
            assertTrue(request("GET", "/shop/of/type?typeId=1", null, null).path("data").size() > 0);
            record("SHOP_QUERY_PASS", "real list/detail/classification/search HTTP", null);

            blogId = request("POST", "/blog", map("shopId", shopId, "title", "PHASE3_TEST_" + RUN,
                    "images", "", "content", "Independent integration fixture", "liked", 0, "comments", 0), tokens.get(0)).path("data").asLong();
            assertTrue(request("GET", "/blog/hot?current=1", null, null).path("data").size() > 0);
            request("PUT", "/blog/like/" + blogId, null, tokens.get(0));
            assertEquals(1, jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=?", Integer.class, blogId));
            assertNotNull(redis.opsForZSet().score("blog:liked:" + blogId, users.get(0).toString()));
            assertTrue(request("GET", "/blog/" + blogId, null, tokens.get(0)).path("data").path("isLike").asBoolean());
            request("PUT", "/blog/like/" + blogId, null, tokens.get(0));
            assertEquals(0, jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=?", Integer.class, blogId));
            assertNull(redis.opsForZSet().score("blog:liked:" + blogId, users.get(0).toString()));
            record("LIKE_PERSISTENCE_PASS", "MySQL 0->1->0; Redis member absent->present->absent", null);

            MessageListenerContainer listener = registry.getListenerContainer("seckillVoucherOrders");
            assertNotNull(listener);
            await("Consumer partition assignment", () -> listener.getAssignedPartitions() != null && !listener.getAssignedPartitions().isEmpty());
            record("KAFKA_CONSUMER_REGISTERED", "running listener; distinct Kafka thread", null);

            long normal = voucher(2);
            assertTrue(request("GET", "/voucher/list/" + shopId, null, null).path("data").size() > 0);
            snapshot("normal_before", normal);
            long first = submit(normal, 0);
            awaitOrder(first);
            assertFalse(request("GET", "/voucher-order/" + first, null, tokens.get(1), false).path("success").asBoolean());
            assertEquals(401, http.getForEntity("/voucher-order/" + first, String.class).getStatusCodeValue());
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                    "INSERT INTO tb_voucher_order(id,user_id,voucher_id,seckill_voucher_id) VALUES(?,?,?,?)", first + 20, users.get(0), normal, normal));
            snapshot("normal_after_first", normal);
            assertStock(normal, 1, 1);
            JsonNode duplicate = request("POST", "/voucher-order/seckill/" + normal, null, tokens.get(0), false);
            assertFalse(duplicate.path("success").asBoolean());
            assertEquals("重复下单", duplicate.path("errorMsg").asText());
            assertStock(normal, 1, 1);
            Map<String, String> message = message(first, users.get(0), normal);
            long repeatedOffset = kafka.send(TOPIC, Long.toString(normal), json.writeValueAsString(message)).get(15, TimeUnit.SECONDS).getRecordMetadata().offset();
            await("duplicate message offset committed", () -> committedOffset() > repeatedOffset);
            assertStock(normal, 1, 1);
            assertEquals(1, countOrders(normal));
            snapshot("normal_after_duplicate_message", normal);
            long second = submit(normal, 1);
            awaitOrder(second);
            assertStock(normal, 0, 0);
            JsonNode soldOut = request("POST", "/voucher-order/seckill/" + normal, null, tokens.get(2), false);
            assertEquals("库存不足", soldOut.path("errorMsg").asText());
            assertEquals(2, countOrders(normal));
            snapshot("normal_sold_out", normal);
            await("normal Kafka offset committed", () -> committedOffset() >= 3);

            long failure = voucher(2);
            trigger = "phase3_fail_" + RUN;
            jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON tb_voucher_order FOR EACH ROW BEGIN IF NEW.voucher_id = "
                    + failure + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'PHASE3_TEST_INSERT_FAILURE'; END IF; END");
            long offsetBeforeFailure = committedOffset();
            long failed = submit(failure, 0);
            await("outbox acknowledged by Kafka", () -> redis.opsForStream().size(OUTBOX) == 0);
            Thread.sleep(1600);
            assertStock(failure, 1, 2);
            assertEquals(0, countOrders(failure));
            assertEquals(offsetBeforeFailure, committedOffset(), "Failed database transaction must not acknowledge Kafka");
            snapshot("insert_failure_rollback_offset_uncommitted", failure);
            jdbc.execute("DROP TRIGGER " + trigger);
            trigger = null;
            awaitOrder(failed);
            await("retry commit", () -> committedOffset() > offsetBeforeFailure);
            assertStock(failure, 1, 1);
            assertEquals(1, countOrders(failure));
            snapshot("insert_failure_recovered", failure);

            // Lua validates wrong/missing keys before decrementing. These are dedicated test keys.
            long uninitialized = voucher(2);
            redis.delete("seckill:stock:" + uninitialized);
            JsonNode unavailable = request("POST", "/voucher-order/seckill/" + uninitialized, null, tokens.get(0), false);
            assertEquals("秒杀库存未初始化", unavailable.path("errorMsg").asText());
            assertEquals(0, countOrders(uninitialized));
            redis.opsForValue().set("seckill:stock:" + uninitialized, "2");
            redis.opsForValue().set("seckill:order:" + uninitialized, "WRONG_TYPE");
            JsonNode wrongType = request("POST", "/voucher-order/seckill/" + uninitialized, null, tokens.get(0), false);
            assertFalse(wrongType.path("success").asBoolean());
            assertStock(uninitialized, 2, 2);
            assertEquals(0, countOrders(uninitialized));
            redis.delete("seckill:order:" + uninitialized);
            record("LUA_TYPE_GUARD_PASS", "result=4; no stock decrement or order mutation", null);
            // Consumer restart recovery is checked with a stopped listener and a real Kafka delivery.
            listener.stop();
            long restartOrder = submit(uninitialized, 0);
            await("restart order sent", () -> redis.opsForStream().size(OUTBOX) == 0);
            assertEquals(0, countOrders(uninitialized));
            snapshot("consumer_stopped_pending", uninitialized);
            listener.start();
            awaitOrder(restartOrder);
            assertStock(uninitialized, 1, 1);
            snapshot("consumer_restarted_recovered", uninitialized);

            long concurrent = voucher(2);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                List<Future<JsonNode>> requests = new ArrayList<>();
                for (String token : tokens) requests.add(pool.submit(() -> request("POST", "/voucher-order/seckill/" + concurrent, null, token, false)));
                int accepted = 0;
                for (Future<JsonNode> response : requests) {
                    JsonNode value = response.get(15, TimeUnit.SECONDS);
                    if (value.path("success").asBoolean()) {
                        long id = Long.parseLong(value.path("data").asText()); orders.add(id); awaitOrder(id); accepted++;
                    } else assertEquals("库存不足", value.path("errorMsg").asText());
                }
                assertEquals(2, accepted);
                assertStock(concurrent, 0, 0);
                assertEquals(2, countOrders(concurrent));
                snapshot("three_users_two_stock", concurrent);
            } finally { pool.shutdownNow(); }

            // Use a real producer connected to an unavailable localhost endpoint, without stopping the shared broker.
            scheduling.postProcessBeforeDestruction(publisher, "seckillOrderPublisher");
            Map<String, Object> unavailableProperties = new HashMap<>();
            unavailableProperties.put("bootstrap.servers", "127.0.0.1:1");
            unavailableProperties.put("key.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
            unavailableProperties.put("value.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
            unavailableProperties.put("max.block.ms", 1000);
            unavailableProperties.put("request.timeout.ms", 500);
            unavailableProperties.put("delivery.timeout.ms", 2000);
            DefaultKafkaProducerFactory<String, String> unavailableFactory = new DefaultKafkaProducerFactory<>(unavailableProperties);
            KafkaTemplate<String, String> unavailableKafka = new KafkaTemplate<>(unavailableFactory);
            long deliveryFailure = voucher(2);
            try {
                ReflectionTestUtils.setField(publisher, "kafkaTemplate", unavailableKafka);
                long delayed = submit(deliveryFailure, 0);
                publisher.publishPendingOrders();
                assertStock(deliveryFailure, 1, 2);
                assertEquals(0, countOrders(deliveryFailure));
                assertTrue(redis.opsForStream().size(OUTBOX) > 0, "Failed send must retain outbox entry");
                snapshot("producer_unavailable_outbox_retained", deliveryFailure);
                ReflectionTestUtils.setField(publisher, "kafkaTemplate", kafka);
                publisher.publishPendingOrders();
                awaitOrder(delayed);
                assertStock(deliveryFailure, 1, 1);
                snapshot("producer_recovered_same_reservation", deliveryFailure);
            } finally {
                ReflectionTestUtils.setField(publisher, "kafkaTemplate", kafka);
                unavailableFactory.destroy();
            }

            org.apache.kafka.common.config.ConfigResource topicConfig = new org.apache.kafka.common.config.ConfigResource(
                    org.apache.kafka.common.config.ConfigResource.Type.TOPIC, TOPIC);
            String retention = admin.describeConfigs(Collections.singleton(topicConfig)).all().get(5, TimeUnit.SECONDS).get(topicConfig).get("retention.ms").value();
            assertEquals("-1", retention);
            record("LUA_SECKILL_PASS", "results 0/1/2/3/4; atomic reservation/outbox", null);
            record("KAFKA_PRODUCER_PASS", "broker acknowledged real JSON messages", null);
            record("KAFKA_CONSUMER_PASS", "real consumption, failure retry, restart, duplicate offset", null);
            record("MYSQL_ORDER_PASS", "transactional inserts and rollback verified", null);
            record("CORE_CHAIN_PASS", "normal, duplicate request, sold out, duplicate Kafka, database failure recovery", null);
        } finally {
            if (trigger != null) jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
            MessageListenerContainer cleanupListener = registry.getListenerContainer("seckillVoucherOrders");
            if (cleanupListener != null) cleanupListener.stop();
            cleanup();
            Map<String, Integer> after = rowCounts();
            record("DATA_CLEANUP", "original row counts restored", map("before", before, "after", after));
            Path path = Paths.get("docs/development/evidence/phase3/integration-tests.json");
            Files.createDirectories(path.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evidence);
            if (admin != null) admin.deleteTopics(Collections.singleton(TOPIC)).all().get(10, TimeUnit.SECONDS);
            if (admin != null) admin.close();
            assertEquals(before, after, "Only independently created fixtures may be removed");
        }
    }

    private void ensureUniqueIndex() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='uk_voucher_order_user_voucher'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND non_unique=0 AND index_name IN ('uk_voucher_order_seckill','uk_voucher_order_request')", Integer.class), "Apply phase5-normal-voucher-repurchase.sql before testing");
    }
    private void login(int index) throws Exception {
        String phone = "199" + RUN.substring(RUN.length() - 7) + index;
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_user WHERE phone=?", Integer.class, phone));
        phones.add(phone);
        request("POST", "/user/code?phone=" + phone, null, null);
        String code = redis.opsForValue().get("login:code:" + phone);
        assertNotNull(code);
        String token = request("POST", "/user/login", map("phone", phone, "code", code), null).path("data").asText();
        tokens.add(token);
        long userId = request("GET", "/user/me", null, token).path("data").path("id").asLong();
        users.add(userId);
        assertEquals(userId, Long.parseLong((String) redis.opsForHash().get("login:token:" + token, "id")));
        assertTrue(redis.getExpire("login:token:" + token) > 0);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_user WHERE id=? AND phone=?", Integer.class, userId, phone));
    }
    private long voucher(int stock) throws Exception {
        JsonNode result = request("POST", "/voucher/seckill", map("shopId", shopId, "title", "PHASE3_TEST_" + RUN,
                "payValue", 1, "actualValue", 1, "type", 1, "status", 1, "stock", stock,
                "beginTime", LocalDateTime.now().minusDays(1).toString(), "endTime", LocalDateTime.now().plusDays(1).toString()), tokens.get(0));
        long id = result.path("data").asLong();
        vouchers.add(id);
        assertStock(id, stock, stock);
        return id;
    }
    private long submit(long voucherId, int user) throws Exception {
        JsonNode response = request("POST", "/voucher-order/seckill/" + voucherId, null, tokens.get(user));
        assertTrue(response.path("data").isTextual(), "64-bit order ID must survive JavaScript losslessly");
        long id = Long.parseLong(response.path("data").asText());
        orders.add(id);
        return id;
    }
    private void awaitOrder(long id) throws Exception {
        await("MySQL order " + id, () -> jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=?", Integer.class, id) == 1);
        await("post-commit Redis state", () -> "CREATED".equals(redis.opsForHash().get(SeckillConstants.RESERVATION + id, "state")));
        JsonNode result = request("GET", "/voucher-order/" + id, null, tokens.get(0), false);
        if (result.path("success").asBoolean()) assertEquals("CREATED", result.path("data").path("state").asText());
    }
    private int countOrders(long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Integer.class, id); }
    private void assertStock(long id, int redisStock, int mysqlStock) {
        assertEquals(Integer.toString(redisStock), redis.opsForValue().get("seckill:stock:" + id));
        assertEquals(mysqlStock, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, id));
    }
    private long committedOffset() {
        try {
            OffsetAndMetadata value = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(new TopicPartition(TOPIC, 0));
            return value == null ? -1 : value.offset();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private JsonNode request(String method, String path, Object body, String token) throws Exception { return request(method, path, body, token, true); }
    private JsonNode request(String method, String path, Object body, String token, boolean expectSuccess) throws Exception {
        return request(method, path, body, token, expectSuccess, null);
    }
    private JsonNode request(String method, String path, Object body, String token, boolean expectSuccess, String requestId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.set("authorization", token);
        if (requestId != null) headers.set("Idempotency-Key", requestId);
        ResponseEntity<String> response = http.exchange(path, HttpMethod.valueOf(method), new HttpEntity<>(body, headers), String.class);
        assertEquals(200, response.getStatusCodeValue(), path);
        JsonNode value = json.readTree(response.getBody());
        if (expectSuccess) assertTrue(value.path("success").asBoolean(), path + " " + value);
        return value;
    }
    private void await(String label, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("Timed out: " + label); Thread.sleep(100); }
    }
    private void snapshot(String name, long voucher) {
        record(name, "inventory/order snapshot", map("voucherId", voucher, "redisStock", redis.opsForValue().get("seckill:stock:" + voucher),
                "mysqlStock", jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucher),
                "orders", countOrders(voucher), "committedOffset", committedOffset()));
    }
    private Map<String, Integer> rowCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String table : Arrays.asList("tb_shop", "tb_user", "tb_blog", "tb_voucher", "tb_seckill_voucher", "tb_voucher_order"))
            result.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        return result;
    }
    private void cleanup() {
        for (long voucher : vouchers) {
            jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?", voucher);
            jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", voucher);
            jdbc.update("DELETE FROM tb_voucher WHERE id=?", voucher);
            redis.delete(Arrays.asList("seckill:stock:" + voucher, "seckill:order:" + voucher));
        }
        for (long order : orders) redis.delete(SeckillConstants.RESERVATION + order);
        if (blogId != 0) { jdbc.update("DELETE FROM tb_blog WHERE id=?", blogId); redis.delete("blog:liked:" + blogId); }
        if (shopId != 0) { jdbc.update("DELETE FROM tb_shop WHERE id=?", shopId); redis.delete(Arrays.asList("cache:shop:" + shopId, "cache:voucher:list:" + shopId)); }
        for (long user : users) jdbc.update("DELETE FROM tb_user WHERE id=?", user);
        for (String token : tokens) redis.delete("login:token:" + token);
        for (String phone : phones) redis.delete("login:code:" + phone);
        redis.delete(OUTBOX); // Run-unique test Stream only; no production Stream is cleared.
    }
    private void record(String check, String detail, Map<String, Object> values) {
        evidence.add(map("check", check, "detail", detail, "values", values));
        System.out.println("PHASE3 " + check + " " + (values == null ? detail : values));
    }
    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
    private Map<String, String> message(long order, long user, long voucher) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", Long.toString(order)); result.put("userId", Long.toString(user)); result.put("voucherId", Long.toString(voucher));
        return result;
    }
}
