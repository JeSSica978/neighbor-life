package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.business.*;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.ReliableEventMapper;
import com.hmdp.messaging.ReliableEventPublisher;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.ReliableEventService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.UserHolder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** 仅显式选中运行：端口、DB marker 双重守卫，业务断言同时查 API、DB 与 Redis。 */
@ActiveProfiles("loadtest")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "hmdp.order.payment-timeout-seconds=120", "hmdp.order.scan-delay-ms=400",
      "hmdp.seckill.publish-delay-ms=100", "hmdp.reliable.publish-delay-ms=200",
      "hmdp.reliable.max-consume-attempts=3", "logging.level.com.hmdp.mapper=WARN",
      "hmdp.rate.purchase.limit=200", "hmdp.media.directory=target/business-test-uploads"
    })
class BusinessClosureIntegrationTest {
  private static final String RUN = "business-" + System.currentTimeMillis();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    if (!"3307".equals(System.getenv("LT_MYSQL_PORT"))
        || !"6380".equals(System.getenv("LT_REDIS_PORT"))
        || !"9093".equals(System.getenv("LT_KAFKA_PORT")))
      throw new IllegalStateException("Explicit isolated environment required");
    p.add("hmdp.seckill.topic", () -> RUN + "-orders");
    p.add("hmdp.seckill.outbox", () -> "loadtest:outbox:" + RUN);
    p.add("spring.kafka.consumer.group-id", () -> RUN + "-orders");
    p.add("hmdp.reliable.cache-topic", () -> RUN + "-cache");
    p.add("hmdp.reliable.stock-topic", () -> RUN + "-stock");
    p.add("hmdp.reliable.cache-group", () -> RUN + "-cache");
    p.add("hmdp.reliable.stock-group", () -> RUN + "-stock");
  }

  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired StringRedisTemplate redis;
  @Autowired BusinessSchema schema;
  @Autowired CommerceService commerce;
  @Autowired CatalogService catalog;
  @Autowired SocialService social;
  @Autowired ReliableEventService events;
  @Autowired ReliableEventPublisher publisher;
  @Autowired ReliableEventMapper eventMapper;
  @Autowired IVoucherOrderService orders;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired com.hmdp.messaging.SeckillOrderPublisher orderPublisher;
  @Autowired KafkaListenerEndpointRegistry listeners;
  @Autowired ScheduledAnnotationBeanPostProcessor scheduler;
  @Autowired PlatformTransactionManager transactions;
  @Autowired CacheClient cache;
  private final List<Long> users = new ArrayList<>(),
      shops = new ArrayList<>(),
      vouchers = new ArrayList<>(),
      blogs = new ArrayList<>();
  private final List<String> tokens = new ArrayList<>(),
      phones = new ArrayList<>(),
      evidence = new ArrayList<>(),
      uploads = new ArrayList<>();
  private AdminClient admin;
  private Map<String, Long> before;
  private long category;

  @Test
  void verifyBusinessClosure() throws Exception {
    http.getRestTemplate()
        .setRequestFactory(
            new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
    try (java.sql.Connection c = dataSource.getConnection()) {
      assertTrue(c.getMetaData().getURL().contains(":3307/hmdp_loadtest"));
    }
    assertEquals(
        "neighborlife-loadtest-v1",
        jdbc.queryForObject("SELECT marker FROM lt_environment WHERE id=1", String.class));
    assertTrue(schema.ready());
    before = counts();
    assertEquals(
        0L,
        before.get("tb_reliable_event").longValue(),
        "Suite requires a drained event table; no existing events are removed");
    admin = AdminClient.create(Collections.singletonMap("bootstrap.servers", "127.0.0.1:9093"));
    boolean passed = false;
    try {
      fixtures();
      assertEquals(
          401, request("POST", "/shop", map("name", "denied"), -1, null).getStatusCodeValue());
      assertEquals(
          403,
          request("PUT", "/shop", map("id", shops.get(0), "name", "denied"), 0, null)
              .getStatusCodeValue());
      assertEquals(
          403,
          request("GET", "/merchant/vouchers?shopId=" + shops.get(0), null, 3, null)
              .getStatusCodeValue());
      evidence.add("AUTH: anonymous writes and cross-shop maintenance rejected");

      long normal = offer(0, 0, 0);
      String key = UUID.randomUUID().toString();
      long one = buy(normal, 0, key), two = buy(normal, 0, UUID.randomUUID().toString());
      assertNotEquals(one, two);
      assertEquals(one, buy(normal, 0, key));
      ok("PATCH", "/merchant/vouchers/" + normal, map("payValue", 5500), 2, null);
      assertEquals(5000, n("SELECT pay_value FROM tb_order_detail WHERE order_id=?", one));
      assertEquals(2000, n("SELECT min_spend FROM tb_voucher_policy WHERE voucher_id=?", normal));
      assertEquals(30, n("SELECT valid_days FROM tb_voucher_policy WHERE voucher_id=?", normal));
      JsonNode pay = payment(one, 0);
      String notice = UUID.randomUUID().toString();
      assertEquals(
          400,
          request(
                  "POST",
                  "/mock-payments/" + pay.path("id").asText() + "/confirm",
                  map("result", "SUCCESS", "amount", 1),
                  0,
                  UUID.randomUUID().toString())
              .getStatusCodeValue());
      confirm(pay, 0, notice);
      confirm(pay, 0, notice);
      confirm(pay, 0, UUID.randomUUID().toString());
      assertEquals(2, status(one));
      assertEquals(1, n("SELECT COUNT(*) FROM tb_payment WHERE order_id=?", one));
      assertEquals(1, n("SELECT COUNT(*) FROM tb_user_voucher WHERE order_id=?", one));
      assertEquals(
          404,
          request("GET", "/payments/" + pay.path("id").asText(), null, 1, null)
              .getStatusCodeValue());
      Map<String, Object> owned =
          jdbc.queryForMap("SELECT * FROM tb_user_voucher WHERE order_id=?", one);
      assertEquals(
          404,
          request("GET", "/user-vouchers/" + owned.get("id"), null, 1, null).getStatusCodeValue());
      assertEquals(
          403,
          request(
                  "POST",
                  "/merchant/redemptions",
                  map("code", owned.get("code"), "consumptionAmount", 8000),
                  3,
                  null)
              .getStatusCodeValue());
      assertEquals(
          409,
          request(
                  "POST",
                  "/merchant/redemptions",
                  map("code", owned.get("code"), "consumptionAmount", 1999),
                  2,
                  null)
              .getStatusCodeValue());
      String redeemKey = UUID.randomUUID().toString();
      JsonNode redemption =
          ok(
                  "POST",
                  "/merchant/redemptions",
                  map("code", owned.get("code"), "consumptionAmount", 8000),
                  2,
                  redeemKey)
              .path("data");
      assertEquals(
          redemption,
          ok(
                  "POST",
                  "/merchant/redemptions",
                  map("code", owned.get("code"), "consumptionAmount", 8000),
                  2,
                  redeemKey)
              .path("data"));
      assertEquals(3, status(one));
      assertEquals(1, n("SELECT COUNT(*) FROM tb_redemption WHERE order_id=?", one));
      assertEquals(
          409,
          request("POST", "/voucher-order/" + one + "/refunds", null, 0, null)
              .getStatusCodeValue());
      ok(
          "POST",
          "/voucher-order/" + one + "/review",
          map("score", 5, "content", "真实核销后的测试评价"),
          0,
          null);
      assertEquals(
          1, ok("GET", "/shop/" + shops.get(0) + "/reviews", null, -1, null).path("total").asInt());
      paymentAndRefund(two, 0);
      evidence.add(
          "NORMAL: multiple orders, request replay, frozen price, payment notice dedupe, issue, scoped redemption, review and refund passed");

      long sale = offer(1, 8, 0), cancel = buy(sale, 0, null);
      await("Kafka order", () -> status(cancel) == 1);
      assertEquals(7, stock(sale));
      assertEquals(7, redisStock(sale));
      JsonNode latePay = payment(cancel, 0);
      ok("POST", "/voucher-order/" + cancel + "/cancel", null, 0, null);
      confirm(latePay, 0, UUID.randomUUID().toString());
      await(
          "late refund and inventory",
          () ->
              n("SELECT COUNT(*) FROM tb_refund WHERE order_id=? AND status='SUCCESS'", cancel) == 1
                  && redisStock(sale) == 8);
      assertEquals(4, status(cancel));
      assertEquals(0, n("SELECT COUNT(*) FROM tb_user_voucher WHERE order_id=?", cancel));
      assertEquals(cancel, buy(sale, 0, null));
      kafka
          .send(
              RUN + "-orders",
              String.valueOf(sale),
              json.writeValueAsString(
                  map(
                      "id",
                      String.valueOf(cancel),
                      "userId",
                      users.get(0).toString(),
                      "voucherId",
                      String.valueOf(sale))))
          .get(10, TimeUnit.SECONDS);
      awaitOffsets();
      assertEquals(8, stock(sale));
      assertEquals(8, redisStock(sale));
      long refunded = buy(sale, 1, null);
      await("second Kafka order", () -> status(refunded) == 1);
      paymentAndRefund(refunded, 1);
      await("refund stock", () -> redisStock(sale) == 8);
      assertEquals(8, stock(sale));
      assertEquals("REFUNDED", redis.opsForHash().get("seckill:reservation:" + refunded, "state"));
      long expiry = buy(sale, 2, null);
      await("expiry Kafka order", () -> status(expiry) == 1);
      jdbc.update(
          "UPDATE tb_order_detail SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE order_id=?",
          expiry);
      await("scheduled timeout", () -> status(expiry) == 4 && redisStock(sale) == 8);
      evidence.add(
          "SECKILL: Lua/Kafka admission, cancel, late-success refund, one-time restoration, dedupe and managed timeout passed");

      long historical = 900000000000L + System.currentTimeMillis();
      jdbc.update(
          "INSERT INTO tb_voucher_order(id,user_id,voucher_id,status,create_time) VALUES(?,?,?,1,TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP))",
          historical,
          users.get(0),
          normal);
      assertTrue(
          ok("GET", "/voucher-order/" + historical, null, 0, null)
              .path("data")
              .path("historical")
              .asBoolean());
      assertEquals(
          409,
          request("POST", "/voucher-order/" + historical + "/cancel", null, 0, null)
              .getStatusCodeValue());
      assertEquals(1, status(historical));
      transactionRollback(sale);
      messagingFailureWindows(sale);
      races(normal);
      blockedNotice(normal);
      contentAndAccount(one);
      cacheAndCatalogRecovery();
      sameMillisecondLimiter();
      awaitEvents();
      awaitOffsets();
      evidence.add("HISTORY: order without detail remains unpaid and read-only");
      passed = true;
    } finally {
      try {
        if (passed) cleanup();
      } catch (Exception | AssertionError e) {
        passed = false;
        throw e;
      } finally {
        Path report = Paths.get("target/non-ai-implementation-20261125/business-evidence.json");
        Files.createDirectories(report.getParent());
        Files.write(
            report,
            json.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(
                    map(
                        "run",
                        RUN,
                        "passed",
                        passed,
                        "checks",
                        evidence,
                        "isolated",
                        true,
                        "users",
                        users,
                        "shops",
                        shops,
                        "vouchers",
                        vouchers,
                        "blogs",
                        blogs)));
        if (admin != null) admin.close();
      }
    }
  }

  private void fixtures() {
    long nonce = System.currentTimeMillis() % 10000000;
    for (int i = 0; i < 5; i++) {
      String phone = String.format("188%08d", nonce * 5 + i);
      phones.add(phone);
      jdbc.update(
          "INSERT INTO tb_user(phone,nick_name,icon) VALUES(?,?,'')", phone, RUN + "-user-" + i);
      long user = n("SELECT id FROM tb_user WHERE phone=?", phone);
      users.add(user);
      redis.opsForValue().set("login:code:" + phone, "684219");
      tokens.add(
          ok("POST", "/user/login", map("phone", phone, "code", "684219"), -1, null)
              .path("data")
              .asText());
    }
    assertFalse(
        okResult("POST", "/user/login", map("phone", phones.get(0), "code", "684219"), -1, null)
            .path("success")
            .asBoolean());
    jdbc.update("INSERT INTO tb_shop_type(name,icon,sort) VALUES(?,'',1)", RUN + "-type");
    category = n("SELECT id FROM tb_shop_type WHERE name=?", RUN + "-type");
    jdbc.update("INSERT INTO tb_admin(user_id) VALUES(?)", users.get(4));
    for (int i = 0; i < 2; i++) {
      // 走真实建店接口，验证旧表无默认值时可创建，并拒绝客户端伪造销量/评分。
      long shop = Long.parseLong(ok("POST", "/shop", map(
          "name", RUN + "-shop-" + i, "typeId", category, "address", "isolated",
          "x", 120.15, "y", 30.27, "sold", 99, "comments", 99, "score", 50), 4, null)
          .path("data").asText());
      shops.add(shop);
      assertEquals(0, n("SELECT sold+comments+score FROM tb_shop WHERE id=?", shop));
    }
    jdbc.update(
        "INSERT INTO tb_shop_member(shop_id,user_id) VALUES(?,?),(?,?)",
        shops.get(0),
        users.get(2),
        shops.get(1),
        users.get(3));
    catalog.sync(shops.get(0), UUID.randomUUID().toString());
    catalog.sync(shops.get(1), UUID.randomUUID().toString());
  }

  private long offer(int type, int stock, int shop) {
    Map<String, Object> body =
        map(
            "shopId",
            shops.get(shop),
            "title",
            RUN + "-offer",
            "rules",
            "未核销可退，满20元可用",
            "payValue",
            5000,
            "actualValue",
            7000,
            "validDays",
            30,
            "minSpend",
            2000,
            "refundAllowed",
            true);
    if (type == 1) {
      body.put("stock", stock);
      body.put("beginTime", java.time.Instant.now().minusSeconds(30).toString());
      body.put("endTime", java.time.Instant.now().plusSeconds(3600).toString());
    }
    long id =
        Long.parseLong(
            ok("POST", type == 1 ? "/voucher/seckill" : "/voucher", body, shop == 0 ? 2 : 3, null)
                .path("data")
                .asText());
    vouchers.add(id);
    return id;
  }

  private long buy(long voucher, int user, String key) {
    String type = n("SELECT type FROM tb_voucher WHERE id=?", voucher) == 1 ? "seckill" : "normal";
    return Long.parseLong(
        ok("POST", "/voucher-order/" + type + "/" + voucher, null, user, key)
            .path("data")
            .asText());
  }

  private JsonNode payment(long order, int user) {
    return ok(
            "POST",
            "/payments",
            map("orderId", String.valueOf(order)),
            user,
            UUID.randomUUID().toString())
        .path("data");
  }

  private void confirm(JsonNode pay, int user, String key) {
    assertEquals(
        "SUCCESS",
        ok(
                "POST",
                "/mock-payments/" + pay.path("id").asText() + "/confirm",
                map("result", "SUCCESS"),
                user,
                key)
            .path("data")
            .path("status")
            .asText());
  }

  private void paymentAndRefund(long order, int user) {
    confirm(payment(order, user), user, UUID.randomUUID().toString());
    String key = UUID.randomUUID().toString();
    JsonNode r = ok("POST", "/voucher-order/" + order + "/refunds", null, user, key).path("data");
    assertEquals("SUCCESS", r.path("status").asText());
    assertEquals(
        r, ok("POST", "/voucher-order/" + order + "/refunds", null, user, key).path("data"));
    assertEquals(6, status(order));
    assertEquals(
        "REFUNDED",
        jdbc.queryForObject(
            "SELECT status FROM tb_user_voucher WHERE order_id=?", String.class, order));
  }

  private void transactionRollback(long voucher) {
    long order = buy(voucher, 3, null);
    await("rollback order", () -> status(order) == 1);
    long beforeStock = stock(voucher);
    Object original = ReflectionTestUtils.getField(events, "events");
    ReflectionTestUtils.setField(events, "events", mock(ReliableEventMapper.class));
    try {
      assertThrows(IllegalStateException.class, () -> commerce.close(order, users.get(3), false));
    } finally {
      ReflectionTestUtils.setField(events, "events", original);
    }
    assertEquals(1, status(order));
    assertEquals(beforeStock, stock(voucher));
    commerce.close(order, users.get(3), false);
    await("rollback repair", () -> redisStock(voucher) == stock(voucher));
    evidence.add(
        "ROLLBACK: event insert failure rolled back status, release ledger and DB inventory together");
  }

  private void races(long normal) throws Exception {
    ExecutorService workers = Executors.newFixedThreadPool(2);
    int refunds = 0, used = 0;
    try {
      for (int i = 0; i < 12; i++) {
        long order = buy(normal, 0, UUID.randomUUID().toString());
        confirm(payment(order, 0), 0, UUID.randomUUID().toString());
        String code =
            jdbc.queryForObject(
                "SELECT code FROM tb_user_voucher WHERE order_id=?", String.class, order);
        CountDownLatch gate = new CountDownLatch(1);
        final boolean favorRefund = i % 2 == 0;
        Future<?> refund =
            workers.submit(
                () -> {
                  try {
                    gate.await();
                    if (!favorRefund) Thread.sleep(15);
                    commerce.refund(order, users.get(0), UUID.randomUUID().toString());
                  } catch (BusinessException expected) {
                    assertEquals(409, expected.getStatus());
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });
        Future<?> redemption =
            workers.submit(
                () -> {
                  UserDTO user = new UserDTO();
                  user.setId(users.get(2));
                  UserHolder.saveUser(user);
                  try {
                    gate.await();
                    if (favorRefund) Thread.sleep(15);
                    commerce.redeem(code, users.get(2), UUID.randomUUID().toString(), 8000);
                  } catch (BusinessException expected) {
                    assertEquals(409, expected.getStatus());
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  } finally {
                    UserHolder.removeUser();
                  }
                });
        gate.countDown();
        refund.get(10, TimeUnit.SECONDS);
        redemption.get(10, TimeUnit.SECONDS);
        int status = status(order);
        assertTrue(status == 3 || status == 6);
        if (status == 6) refunds++;
        else used++;
        assertEquals(
            1,
            n(
                "SELECT (SELECT COUNT(*) FROM tb_refund WHERE order_id=?)+(SELECT COUNT(*) FROM tb_redemption WHERE order_id=?)",
                order,
                order));
      }
    } finally {
      workers.shutdownNow();
    }
    assertTrue(refunds > 0 && used > 0);
    evidence.add(
        "RACE: 12 controlled refund/redemption races; refund="
            + refunds
            + ", redemption="
            + used
            + "; exactly one terminal operation");
  }

  @SuppressWarnings("unchecked") // Mockito 创建的 KafkaTemplate 代理会擦除泛型，仅在该故障注入方法内使用。
  private void messagingFailureWindows(long sale) throws Exception {
    scheduler.postProcessBeforeDestruction(orderPublisher, "seckillOrderPublisher");
    Object realKafka = ReflectionTestUtils.getField(orderPublisher, "kafkaTemplate");
    KafkaTemplate<String, String> broken = mock(KafkaTemplate.class);
    when(broken.send(anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("injected broker send failure"));
    ReflectionTestUtils.setField(orderPublisher, "kafkaTemplate", broken);
    long before = stock(sale), accepted;
    try {
      accepted = buy(sale, 4, null);
      orderPublisher.publishPendingOrders();
      assertEquals(0, status(accepted));
      assertEquals(before, stock(sale));
      assertEquals(before - 1, redisStock(sale));
      assertEquals(1, redis.opsForStream().size("loadtest:outbox:" + RUN));
    } finally {
      ReflectionTestUtils.setField(orderPublisher, "kafkaTemplate", realKafka);
    }
    Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(orders);
    com.hmdp.mapper.VoucherOrderMapper realMapper =
        (com.hmdp.mapper.VoucherOrderMapper) ReflectionTestUtils.getField(target, "baseMapper");
    com.hmdp.mapper.VoucherOrderMapper brokenMapper =
        mock(
            com.hmdp.mapper.VoucherOrderMapper.class,
            org.mockito.AdditionalAnswers.delegatesTo(realMapper));
    doThrow(new IllegalStateException("injected INSERT failure"))
        .when(brokenMapper)
        .insertSeckill(any(VoucherOrder.class));
    ReflectionTestUtils.setField(target, "baseMapper", brokenMapper);
    try {
      orderPublisher.publishPendingOrders();
      await(
          "Kafka retained on insert failure",
          () -> {
            try {
              TopicPartition partition = new TopicPartition(RUN + "-orders", 0);
              Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> c =
                  admin
                      .listConsumerGroupOffsets(RUN + "-orders")
                      .partitionsToOffsetAndMetadata()
                      .get(3, TimeUnit.SECONDS);
              long end =
                  admin
                      .listOffsets(
                          Collections.singletonMap(
                              partition, org.apache.kafka.clients.admin.OffsetSpec.latest()))
                      .all()
                      .get(3, TimeUnit.SECONDS)
                      .get(partition)
                      .offset();
              return end > 0 && c.get(partition) != null && c.get(partition).offset() < end;
            } catch (Exception e) {
              return false;
            }
          });
      Thread.sleep(1200);
      assertEquals(0, status(accepted));
      assertEquals(before, stock(sale));
      assertEquals(before - 1, redisStock(sale));
    } finally {
      ReflectionTestUtils.setField(target, "baseMapper", realMapper);
      scheduler.postProcessAfterInitialization(orderPublisher, "seckillOrderPublisher");
    }
    await("same admission recovered", () -> status(accepted) == 1);
    assertEquals(before - 1, stock(sale));
    awaitOffsets();
    commerce.close(accepted, users.get(4), false);
    await("failed window stock converged", () -> redisStock(sale) == before);
    evidence.add(
        "MESSAGING: failed Kafka send retained Lua outbox; failed DB INSERT rolled back inventory and withheld offset; restored dependencies committed the original admission once");
  }

  private void blockedNotice(long normal) {
    long order = buy(normal, 0, UUID.randomUUID().toString());
    JsonNode payment = payment(order, 0);
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO tb_payment_notice(id,payment_id,result,amount,state,attempts) VALUES(?,?,'SUCCESS',1,3,8)",
        id,
        payment.path("id").asText());
    commerce.confirm(payment.path("id").asText(), users.get(0), id, "SUCCESS", null);
    assertEquals(1, status(order));
    assertEquals(3, n("SELECT state FROM tb_payment_notice WHERE id=?", id));
    jdbc.update(
        "UPDATE tb_payment_notice SET amount=? WHERE id=?", payment.path("amount").asLong(), id);
    ok("POST", "/admin/payment-notices/" + id + "/retry", null, 4, null);
    await(
        "notice replay",
        () -> status(order) == 2 && n("SELECT state FROM tb_payment_notice WHERE id=?", id) == 2);
    assertEquals(1, n("SELECT COUNT(*) FROM tb_user_voucher WHERE order_id=?", order));
    evidence.add(
        "NOTICE: regular replay could not bypass durable quarantine; controlled repair and administrator retry issued once");
  }

  private void contentAndAccount(long usedOrder) throws Exception {
    ok("PUT", "/shop/" + shops.get(0) + "/favorite", null, 0, null);
    ok("PUT", "/shop/" + shops.get(0) + "/favorite", null, 0, null);
    assertEquals(1, ok("GET", "/user/favorites", null, 0, null).path("total").asInt());
    assertEquals(0, ok("GET", "/user/favorites", null, 1, null).path("total").asInt());
    ok("PUT", "/follow/" + users.get(1) + "/true", null, 0, null);
    ok("PUT", "/follow/" + users.get(1) + "/true", null, 0, null);
    for (int i = 0; i < 13; i++) {
      long id =
          Long.parseLong(
              ok(
                      "POST",
                      "/blog",
                      map(
                          "shopId",
                          shops.get(0),
                          "title",
                          RUN + "-blog-" + i,
                          "content",
                          "正文 <script>alert('x')</script>",
                          "images",
                          ""),
                      1,
                      null)
                  .path("data")
                  .asText());
      blogs.add(id);
    }
    jdbc.update(
        "UPDATE tb_blog SET create_time=FROM_UNIXTIME(?) WHERE user_id=?",
        System.currentTimeMillis() / 1000,
        users.get(1));
    social.sync("FOLLOW_SYNC", users.get(0));
    JsonNode page1 =
        ok("GET", "/blog/of/follow?lastId=" + System.currentTimeMillis(), null, 0, null)
            .path("data");
    JsonNode page2 =
        ok(
                "GET",
                "/blog/of/follow?lastId="
                    + page1.path("minTime").asLong()
                    + "&offset="
                    + page1.path("offset").asInt(),
                null,
                0,
                null)
            .path("data");
    Set<String> ids = new HashSet<>();
    page1.path("list").forEach(b -> assertTrue(ids.add(b.path("id").asText())));
    page2.path("list").forEach(b -> assertTrue(ids.add(b.path("id").asText())));
    assertEquals(13, ids.size());
    long blog = blogs.get(0);
    ok("PUT", "/blog/" + blog + "/like", map("liked", true), 0, null);
    ok("PUT", "/blog/" + blog + "/like", map("liked", true), 0, null);
    assertEquals(1, n("SELECT liked FROM tb_blog WHERE id=?", blog));
    long comment =
        Long.parseLong(
            ok("POST", "/blog/" + blog + "/comments", map("content", "评论测试"), 0, null)
                .path("data")
                .asText());
    ok("POST", "/blog/" + blog + "/comments", map("content", "回复测试", "parentId", comment), 1, null);
    assertEquals(
        403, request("DELETE", "/blog-comments/" + comment, null, 1, null).getStatusCodeValue());
    ok("DELETE", "/blog-comments/" + comment, null, 0, null);
    assertEquals(1, n("SELECT comments FROM tb_blog WHERE id=?", blog));
    assertEquals(
        403,
        request("PATCH", "/blog/" + blog, map("title", "bad", "content", "bad"), 0, null)
            .getStatusCodeValue());
    ok("DELETE", "/blog/" + blog, null, 1, null);
    assertEquals(404, request("GET", "/blog/" + blog, null, -1, null).getStatusCodeValue());
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    for (int i = 0; i < today.getDayOfMonth() + 2; i++) {
      java.time.LocalDate day = today.minusDays(i);
      String key =
          "sign:"
              + users.get(0)
              + day.format(java.time.format.DateTimeFormatter.ofPattern(":yyyyMM"));
      redis.opsForValue().setBit(key, day.getDayOfMonth() - 1, true);
    }
    ok("POST", "/user/sign", null, 0, null);
    assertEquals(
        today.getDayOfMonth() + 2,
        ok("GET", "/user/sign/count", null, 0, null).path("data").asInt());
    ok("PATCH", "/user/me", map("nickName", "新昵称", "city", "杭州", "introduce", "测试介绍"), 0, null);
    assertEquals(
        "测试介绍", ok("GET", "/user/me", null, 0, null).path("data").path("introduce").asText());
    assertEquals(
        2,
        ok("GET", "/shop/of/name?x=120.15&y=30.27&size=12", null, -1, null).path("total").asInt());
    assertEquals(
        400, request("GET", "/shop/of/name?x=999&y=30", null, -1, null).getStatusCodeValue());
    imageUpload();
    ok("POST", "/user/logout", null, 0, null);
    assertEquals(401, request("GET", "/user/me", null, 0, null).getStatusCodeValue());
    // Restore a fixture session for the remaining checks without exposing it in the report.
    redis.opsForValue().set("login:code:" + phones.get(0), "684219");
    tokens.set(
        0,
        ok("POST", "/user/login", map("phone", phones.get(0), "code", "684219"), -1, null)
            .path("data")
            .asText());
    evidence.add(
        "COMMUNITY: account-scoped favorites, persisted follows/likes, 13 equal-score feed items, replies/ownership, cross-month Bitmap, profile, GEO and logout passed");
  }

  private void imageUpload() throws Exception {
    byte[] data =
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jY9sAAAAASUVORK5CYII=");
    org.springframework.util.MultiValueMap<String, Object> body =
        new org.springframework.util.LinkedMultiValueMap<>();
    body.add(
        "file",
        new org.springframework.core.io.ByteArrayResource(data) {
          public String getFilename() {
            return "../../image.png";
          }
        });
    HttpHeaders headers = new HttpHeaders();
    headers.set("authorization", tokens.get(0));
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<String> response =
        http.exchange(
            "/upload/blog", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    assertEquals(200, response.getStatusCodeValue());
    String path = json.readTree(response.getBody()).path("data").asText();
    assertTrue(path.matches("/media/[a-f0-9-]{36}\\.png"));
    uploads.add(path);
    assertEquals(200, http.getForEntity(path, byte[].class).getStatusCodeValue());
    assertEquals(
        404, request("DELETE", "/upload/blog?name=" + path, null, 1, null).getStatusCodeValue());
    ok("DELETE", "/upload/blog?name=" + path, null, 0, null);
    evidence.add(
        "UPLOAD: authenticated decode/re-encode, generated path, public image serving and owner-only deletion passed");
  }

  private void cacheAndCatalogRecovery() {
    long shop = shops.get(0);
    ok("GET", "/shop/" + shop, null, -1, null);
    ok("PUT", "/shop", map("id", shop, "name", RUN + "-renamed"), 2, null);
    assertEquals(
        RUN + "-renamed",
        ok("GET", "/shop/" + shop, null, -1, null).path("data").path("name").asText());
    Object real = ReflectionTestUtils.getField(catalog, "redis");
    StringRedisTemplate broken =
        mock(
            StringRedisTemplate.class,
            invocation -> {
              throw new RedisConnectionFailureException("injected isolated dependency fault");
            });
    ReflectionTestUtils.setField(catalog, "redis", broken);
    long voucher;
    try {
      voucher = offer(1, 3, 0);
      await(
          "durable blocked catalog",
          () ->
              n("SELECT COUNT(*) FROM tb_reliable_event WHERE resource_id=? AND status=3", shop)
                  > 0);
    } finally {
      ReflectionTestUtils.setField(catalog, "redis", real);
    }
    assertEquals(0, n("SELECT ready FROM tb_voucher_policy WHERE voucher_id=?", voucher));
    redis.opsForValue().set("seckill:stock:" + voucher, "99");
    String event =
        jdbc.queryForObject(
            "SELECT id FROM tb_reliable_event WHERE resource_id=? AND status=3 ORDER BY create_time DESC LIMIT 1",
            String.class,
            shop);
    ok("POST", "/admin/events/" + event + "/retry", null, 4, null);
    await(
        "initial mismatch blocked",
        () -> n("SELECT status FROM tb_reliable_event WHERE id=?", event) == 3);
    assertEquals(99, redisStock(voucher));
    assertEquals(0, n("SELECT ready FROM tb_voucher_policy WHERE voucher_id=?", voucher));
    redis.delete("seckill:stock:" + voucher);
    ok("POST", "/admin/events/" + event + "/retry", null, 4, null);
    await(
        "catalog recovery",
        () ->
            n("SELECT ready FROM tb_voucher_policy WHERE voucher_id=?", voucher) == 1
                && n("SELECT status FROM tb_reliable_event WHERE id=?", event) == 2);
    assertEquals(3, redisStock(voucher));
    String key = "cache:test:" + RUN;
    CountDownLatch started = new CountDownLatch(1), resume = new CountDownLatch(1);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<?> stale =
          worker.submit(
              () ->
                  cache.queryWithLogicalExpire(
                      key,
                      "1",
                      String.class,
                      id -> {
                        started.countDown();
                        try {
                          resume.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return "old";
                      },
                      30L,
                      TimeUnit.SECONDS));
      try {
        assertTrue(started.await(5, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      redis.opsForValue().increment(key + "1:version");
      resume.countDown();
      try {
        stale.get(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      assertNull(redis.opsForValue().get(key + "1"));
    } finally {
      resume.countDown();
      worker.shutdownNow();
      redis.delete(Arrays.asList(key + "1", key + "1:version", "lock:cache:" + key + "1"));
    }
    evidence.add(
        "CACHE: cold read, merchant update, stale-reader version rejection, real Kafka quarantine/requeue and initial inventory mismatch protection passed");
  }

  private void sameMillisecondLimiter() {
    com.hmdp.limiter.aop.RateLimiterAspect limiter = new com.hmdp.limiter.aop.RateLimiterAspect();
    ReflectionTestUtils.setField(limiter, "stringRedisTemplate", redis);
    String key = "test:limit:" + RUN;
    try {
      for (int i = 0; i < 10; i++)
        assertEquals(i + 1, limiter.executeSlidingWindowScript(key, 5L, 10L));
      assertEquals(0, limiter.executeSlidingWindowScript(key, 5L, 10L));
      assertEquals(10, redis.opsForZSet().zCard(key));
    } finally {
      redis.delete(key);
    }
    evidence.add(
        "LIMITER: distinct requests remain separately counted, window boundary rejects excess");
  }

  private void awaitEvents() {
    await(
        "all reliable events",
        () -> n("SELECT COUNT(*) FROM tb_reliable_event WHERE status<>2") == 0);
  }

  private void awaitOffsets() {
    for (String suffix : Arrays.asList("orders", "cache", "stock")) {
      String topic = RUN + "-" + suffix;
      await(
          "Kafka committed " + suffix,
          () -> {
            try {
              Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed =
                  admin
                      .listConsumerGroupOffsets(topic)
                      .partitionsToOffsetAndMetadata()
                      .get(3, TimeUnit.SECONDS);
              Map<
                      TopicPartition,
                      org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo>
                  end =
                      admin
                          .listOffsets(
                              Collections.singletonMap(
                                  new TopicPartition(topic, 0),
                                  org.apache.kafka.clients.admin.OffsetSpec.latest()))
                          .all()
                          .get(3, TimeUnit.SECONDS);
              long latest = end.get(new TopicPartition(topic, 0)).offset();
              return latest == 0
                  || committed.containsKey(new TopicPartition(topic, 0))
                      && committed.get(new TopicPartition(topic, 0)).offset() == latest;
            } catch (Exception e) {
              return false;
            }
          });
    }
  }

  private void cleanup() {
    scheduler.destroy();
    listeners.stop();
    List<Long> orderIds =
        jdbc.queryForList(
            "SELECT id FROM tb_voucher_order WHERE user_id IN (" + marks(users) + ")",
            Long.class,
            users.toArray());
    List<String> paymentIds = new ArrayList<>();
    for (Long order : orderIds)
      paymentIds.addAll(
          jdbc.queryForList("SELECT id FROM tb_payment WHERE order_id=?", String.class, order));
    for (String id : paymentIds)
      jdbc.update("DELETE FROM tb_payment_notice WHERE payment_id=?", id);
    for (Long order : orderIds) {
      for (String table :
          Arrays.asList(
              "tb_redemption",
              "tb_refund",
              "tb_user_voucher",
              "tb_payment",
              "tb_stock_ledger",
              "tb_order_detail",
              "tb_shop_review")) jdbc.update("DELETE FROM " + table + " WHERE order_id=?", order);
      redis.delete(Arrays.asList("seckill:reservation:" + order, "order:stock-restored:" + order));
    }
    for (Long blog : blogs) {
      jdbc.update("DELETE FROM tb_blog_comments WHERE blog_id=?", blog);
      jdbc.update("DELETE FROM tb_blog_like WHERE blog_id=?", blog);
      jdbc.update("DELETE FROM tb_blog_visibility WHERE blog_id=?", blog);
      jdbc.update("DELETE FROM tb_blog WHERE id=?", blog);
      redis.delete("blog:liked:" + blog);
    }
    for (Long user : users) {
      jdbc.update("DELETE FROM tb_voucher_order WHERE user_id=?", user);
      jdbc.update("DELETE FROM tb_favorite WHERE user_id=?", user);
      jdbc.update("DELETE FROM tb_social_follow WHERE user_id=? OR follow_user_id=?", user, user);
      jdbc.update("DELETE FROM tb_shop_member WHERE user_id=?", user);
      jdbc.update("DELETE FROM tb_admin WHERE user_id=?", user);
      jdbc.update("DELETE FROM tb_operation_audit WHERE operator_id=?", user);
      jdbc.update("DELETE FROM tb_upload WHERE user_id=?", user);
      jdbc.update("DELETE FROM tb_user_info WHERE user_id=?", user);
      jdbc.update("DELETE FROM tb_user WHERE id=?", user);
      redis.delete(Arrays.asList("follows:" + user, "feed:" + user));
      Set<String> signs = redis.keys("sign:" + user + ":*");
      if (signs != null && !signs.isEmpty()) redis.delete(signs);
    }
    for (Long voucher : vouchers) {
      jdbc.update("DELETE FROM tb_voucher_policy WHERE voucher_id=?", voucher);
      jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", voucher);
      jdbc.update("DELETE FROM tb_voucher WHERE id=?", voucher);
      redis.delete(
          Arrays.asList(
              "seckill:stock:" + voucher,
              "seckill:order:" + voucher,
              "seckill:request:" + voucher));
    }
    List<String> reliable = jdbc.queryForList("SELECT id FROM tb_reliable_event", String.class);
    for (String id : reliable) redis.delete("cache:applied:" + id);
    for (Long shop : shops) {
      jdbc.update("DELETE FROM tb_shop_settings WHERE shop_id=?", shop);
      jdbc.update("DELETE FROM tb_shop WHERE id=?", shop);
      redis.opsForGeo().remove("shop:geo:" + category, shop.toString());
      Set<String> keys = redis.keys("cache:*" + shop + "*");
      if (keys != null && !keys.isEmpty()) redis.delete(keys);
      Set<String> uv = redis.keys("uv:shop:" + shop + ":*");
      if (uv != null && !uv.isEmpty()) redis.delete(uv);
    }
    jdbc.update("DELETE FROM tb_shop_type WHERE id=?", category);
    // Isolated suite owns every new event in this initially clean fixture DB; assert that before
    // removing them.
    assertEquals(0, before.get("tb_reliable_event"));
    jdbc.update("DELETE FROM tb_reliable_event");
    for (String token : tokens) redis.delete("login:token:" + token);
    for (String phone : phones) redis.delete("login:code:" + phone);
    assertEquals(before, counts());
    evidence.add("CLEANUP: fixture table counts restored; topics/group offsets retained");
  }

  private Map<String, Long> counts() {
    Map<String, Long> c = new LinkedHashMap<>();
    for (String t :
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'tb_%'",
            String.class)) c.put(t, n("SELECT COUNT(*) FROM " + t));
    return c;
  }

  private String marks(List<?> list) {
    return String.join(",", Collections.nCopies(list.size(), "?"));
  }

  private long n(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  private int status(long id) {
    return (int) n("SELECT COALESCE(MAX(status),0) FROM tb_voucher_order WHERE id=?", id);
  }

  private long stock(long id) {
    return n("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", id);
  }

  private long redisStock(long id) {
    String value = redis.opsForValue().get("seckill:stock:" + id);
    return value == null ? -1 : Long.parseLong(value);
  }

  private void await(String name, BooleanSupplier condition) {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
    while (System.nanoTime() < end) {
      if (condition.getAsBoolean()) return;
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(name);
      }
    }
    fail("Timeout: " + name);
  }

  private ResponseEntity<String> request(
      String method, String path, Object body, int user, String key) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    if (user >= 0) h.set("authorization", tokens.get(user));
    if (key != null) h.set("Idempotency-Key", key);
    try {
      return http.exchange(
          path,
          HttpMethod.valueOf(method),
          new HttpEntity<>(body == null ? null : json.writeValueAsString(body), h),
          String.class);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private JsonNode okResult(String method, String path, Object body, int user, String key) {
    try {
      return json.readTree(request(method, path, body, user, key).getBody());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private JsonNode ok(String method, String path, Object body, int user, String key) {
    ResponseEntity<String> response = request(method, path, body, user, key);
    assertEquals(
        200, response.getStatusCodeValue(), method + " " + path + " " + response.getBody());
    try {
      JsonNode result = json.readTree(response.getBody());
      assertTrue(result.path("success").asBoolean(), result.toString());
      return result;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static Map<String, Object> map(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i].toString(), pairs[i + 1]);
    return m;
  }
}
