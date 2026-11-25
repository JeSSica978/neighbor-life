package com.hmdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ReliableEventMapper;
import com.hmdp.messaging.*;
import com.hmdp.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicitly selected, guarded integration suite. Never connects to development services. */
@ActiveProfiles("loadtest")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "hmdp.order.payment-timeout-seconds=60", "hmdp.order.scan-delay-ms=500",
        "hmdp.seckill.publish-delay-ms=100", "hmdp.reliable.max-consume-attempts=3",
        "hmdp.demo.shop-edit-enabled=true", "logging.level.com.hmdp.mapper=WARN"})
class OrderLifecycleIntegrationTest {
    private static final String RUN = "lifecycle-" + System.currentTimeMillis();
    private static final long SHOP = 3000000 + System.currentTimeMillis() % 10000000;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        if (!"3307".equals(System.getenv("LT_MYSQL_PORT")) || !"6380".equals(System.getenv("LT_REDIS_PORT"))
                || !"9093".equals(System.getenv("LT_KAFKA_PORT"))) throw new IllegalStateException("Explicit isolated ports required");
        p.add("hmdp.seckill.topic", () -> RUN+"-orders"); p.add("hmdp.seckill.outbox", () -> "loadtest:outbox:"+RUN);
        p.add("spring.kafka.consumer.group-id", () -> RUN+"-orders");
        p.add("hmdp.reliable.cache-topic", () -> RUN+"-cache"); p.add("hmdp.reliable.stock-topic", () -> RUN+"-stock");
        p.add("hmdp.reliable.cache-group", () -> RUN+"-cache"); p.add("hmdp.reliable.stock-group", () -> RUN+"-stock");
        p.add("hmdp.demo.shop-ids", () -> String.valueOf(SHOP));
    }
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired StringRedisTemplate redis;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ReliableEventService events;
    @Autowired ReliableEventPublisher publisher;
    @Autowired OrderAutoCloseJob job;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired IShopService shops;
    @Autowired ReliableEventMapper eventMapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduling;
    private final List<Long> vouchers=new ArrayList<>(), users=new ArrayList<>(), orderIds=new ArrayList<>();
    private final List<String> tokens=new ArrayList<>(), phones=new ArrayList<>();
    private final List<Map<String,Object>> evidence=new ArrayList<>();
    private AdminClient admin;

    @Test void lifecycleAndCompensation() throws Exception {
        try(java.sql.Connection c=dataSource.getConnection()) {
            assertTrue(c.getMetaData().getURL().contains(":3307/hmdp_loadtest"));
        }
        assertEquals("neighborlife-loadtest-v1", jdbc.queryForObject("SELECT marker FROM lt_environment WHERE id=1",String.class));
        Map<String,Integer> before=counts();
        admin=AdminClient.create(Collections.singletonMap("bootstrap.servers","127.0.0.1:9093"));
        Object originalRedis=ReflectionTestUtils.getField(events,"redis");
        Object originalMapper=ReflectionTestUtils.getField(events,"events");
        Object originalKafka=ReflectionTestUtils.getField(publisher,"kafka");
        try {
            scheduling.postProcessBeforeDestruction(publisher,"reliableEventPublisher"); // deterministic fault injection; listeners remain real
            jdbc.update("INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) VALUES(?, ?,1,'','isolated',1,1,0,0,50)",SHOP,RUN);
            for(int i=0;i<3;i++) login(i);
            assertEquals(401,http.getForEntity("/voucher-order/mine",String.class).getStatusCodeValue());
            assertNotNull(listeners.getListenerContainer("shopCacheDelete"));
            assertNotNull(listeners.getListenerContainer("orderStockRestore"));

            long expiring=voucher(2,1), expiredOrder=submit(expiring,0);
            jdbc.update("UPDATE tb_voucher_order SET create_time=TIMESTAMPADD(SECOND,-70,CURRENT_TIMESTAMP) WHERE id=?",expiredOrder);
            await("scheduled close",()->status(expiredOrder)==4);
            snapshot("SPRING_TASK_CLOSE", expiring);
            assertEquals(2, stock(expiring)); assertEquals(1, redisStock(expiring));
            publishAndDrain(); assertEquals(2,redisStock(expiring));
            for(int i=0;i<3;i++) ok("POST","/voucher-order/"+expiredOrder+"/cancel",null,0);
            assertEquals(2,stock(expiring)); assertEquals(2,redisStock(expiring));
            assertFalse(request("POST","/voucher-order/"+expiredOrder+"/pay/simulate",null,0).path("success").asBoolean());
            assertEquals("重复下单",request("POST","/voucher-order/seckill/"+expiring,null,0).path("errorMsg").asText());
            kafka.send(RUN+"-orders",String.valueOf(expiring),json.writeValueAsString(map("id",String.valueOf(expiredOrder),"userId",users.get(0).toString(),"voucherId",String.valueOf(expiring)))).get(5,TimeUnit.SECONDS);
            Thread.sleep(1000);assertEquals(2,stock(expiring));assertEquals(2,redisStock(expiring));
            assertEquals("CANCELLED",redis.opsForHash().get("seckill:reservation:"+expiredOrder,"state"));
            record("EXPIRED_CLOSE_IDEMPOTENT", "closed once; Redis/MySQL restored; repurchase rejected");
            scheduling.postProcessBeforeDestruction(job,"orderAutoCloseJob");

            long passiveVoucher=voucher(1,1), passiveOrder=submit(passiveVoucher,0);
            jdbc.update("UPDATE tb_voucher_order SET create_time=TIMESTAMPADD(SECOND,-70,CURRENT_TIMESTAMP) WHERE id=?",passiveOrder);
            assertEquals(4,ok("GET","/voucher-order/"+passiveOrder,null,0).path("data").path("status").asInt());
            publishAndDrain(); assertEquals(1,redisStock(passiveVoucher)); record("PASSIVE_CLOSE_PASS","owner GET closes expired order");

            long paidVoucher=voucher(1,1), paidOrder=submit(paidVoucher,0);
            assertEquals(2,ok("POST","/voucher-order/"+paidOrder+"/pay/simulate",null,0).path("data").path("status").asInt());
            ok("POST","/voucher-order/"+paidOrder+"/pay/simulate",null,0);
            assertFalse(request("POST","/voucher-order/"+paidOrder+"/cancel",null,0).path("success").asBoolean());
            assertEquals(0,stock(paidVoucher)); assertEquals(0,redisStock(paidVoucher));
            assertEquals(0,jdbc.queryForObject("SELECT pay_type FROM tb_voucher_order WHERE id=?",Integer.class,paidOrder));
            assertFalse(request("POST","/voucher-order/"+paidOrder+"/pay/simulate",null,1).path("success").asBoolean());
            assertFalse(request("GET","/voucher-order/"+paidOrder,null,1).path("success").asBoolean());
            record("PAYMENT_AND_OWNER_PASS","repeated simulated pay succeeds; paid close rejected; other user denied");

            int paidWins=0,closedWins=0;
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                for(int i=0;i<20;i++) {
                    long v=voucher(1,1), id=submit(v,0);
                    CountDownLatch gate=new CountDownLatch(1);
                    final boolean favorClose=i%2==1;
                    Future<Integer> p=pool.submit(()->{gate.await();if(favorClose)Thread.sleep(10);return lifecycle.pay(id,users.get(0)).getStatus();});
                    Future<Boolean> c=pool.submit(()->{gate.await();if(!favorClose)Thread.sleep(10);return lifecycle.close(id,users.get(0),false);});
                    gate.countDown(); p.get(10,TimeUnit.SECONDS); boolean closed=c.get(10,TimeUnit.SECONDS);
                    int state=status(id); assertTrue(state==2||state==4);
                    assertEquals(closed,state==4); assertEquals(state==4?1:0,stock(v));
                    if(state==2) paidWins++; else closedWins++;
                }
            } finally {pool.shutdownNow();}
            publishAndDrain();
            for(Long v:vouchers) assertEquals(stock(v),redisStock(v));
            Map<String,Object> race=new LinkedHashMap<>();race.put("test","PAY_CLOSE_RACE");race.put("rounds",20);race.put("controlledInterleaving","alternating 10ms start delay; both workers compete using the same CAS");race.put("paidWins",paidWins);race.put("closedWins",closedWins);evidence.add(race);

            long rollbackVoucher=voucher(1,1), rollbackOrder=submit(rollbackVoucher,0);
            assertFalse(request("POST","/voucher-order/"+rollbackOrder+"/cancel",null,1).path("success").asBoolean());
            assertEquals(1,status(rollbackOrder));
            ReliableEventMapper broken=mock(ReliableEventMapper.class); // event insert returns 0, forcing transaction rollback
            ReflectionTestUtils.setField(events,"events",broken);
            assertThrows(IllegalStateException.class,()->lifecycle.close(rollbackOrder,users.get(0),false));
            ReflectionTestUtils.setField(events,"events",originalMapper);
            assertEquals(1,status(rollbackOrder));assertEquals(0,stock(rollbackVoucher));assertEquals(0,redisStock(rollbackVoucher));
            record("CLOSE_TRANSACTION_ROLLBACK","event insert failure rolls back status AND MySQL stock");

            long normal=voucher(0,0), normalOrder=Long.parseLong(ok("POST","/voucher-order/normal/"+normal,null,0).path("data").asText());
            orderIds.add(normalOrder); ok("POST","/voucher-order/"+normalOrder+"/cancel",null,0);
            assertEquals(4,status(normalOrder));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tb_reliable_event WHERE resource_id=?",Integer.class,normalOrder));
            record("NORMAL_ORDER_CLOSE_PASS","normal order cancels without inventing normal stock");

            String purchaseKey=UUID.randomUUID().toString();
            long another=normalPurchase(normal,0,purchaseKey);orderIds.add(another);
            assertNotEquals(normalOrder,another);assertEquals(another,normalPurchase(normal,0,purchaseKey));
            ok("POST","/voucher-order/"+another+"/pay/simulate",null,0);
            long afterPayment=normalPurchase(normal,0,UUID.randomUUID().toString());orderIds.add(afterPayment);
            assertNotEquals(another,afterPayment);assertEquals(1,status(afterPayment));
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=? AND voucher_id=?",Integer.class,users.get(0),normal));

            ExecutorService purchases=Executors.newFixedThreadPool(8);
            try {
                String retryKey=UUID.randomUUID().toString();
                CountDownLatch retryGate=new CountDownLatch(1);
                List<Future<Long>> retries=new ArrayList<>();
                for(int i=0;i<8;i++) retries.add(purchases.submit(()->{retryGate.await();return normalPurchase(normal,1,retryKey);}));
                retryGate.countDown();Set<Long> retriedIds=new HashSet<>();
                for(Future<Long> retry:retries) retriedIds.add(retry.get(10,TimeUnit.SECONDS));
                assertEquals(1,retriedIds.size());orderIds.addAll(retriedIds);
                CountDownLatch purchaseGate=new CountDownLatch(1);
                List<Future<Long>> independent=new ArrayList<>();
                for(int i=0;i<8;i++) independent.add(purchases.submit(()->{purchaseGate.await();return normalPurchase(normal,1,UUID.randomUUID().toString());}));
                purchaseGate.countDown();Set<Long> purchasedIds=new HashSet<>();
                for(Future<Long> purchase:independent) purchasedIds.add(purchase.get(10,TimeUnit.SECONDS));
                assertEquals(8,purchasedIds.size());orderIds.addAll(purchasedIds);
            } finally {purchases.shutdownNow();}
            assertEquals(12,jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?",Integer.class,normal));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id=?",Integer.class,normal));
            assertFalse(redis.hasKey("seckill:stock:"+normal));assertFalse(redis.hasKey("seckill:order:"+normal));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                    "INSERT INTO tb_voucher_order(id,user_id,voucher_id,seckill_voucher_id) VALUES(?,?,?,?)",expiredOrder+100,users.get(0),expiring,expiring));
            assertEquals(2,stock(expiring));assertEquals(2,redisStock(expiring));
            record("NORMAL_REPURCHASE_PASS","new purchases after cancellation/payment create distinct orders; 8 retries create 1 order; 8 separate purchases create 8; seckill uniqueness/inventory retained");

            ok("GET","/shop/"+SHOP,null,0); assertTrue(redis.getExpire("cache:shop:"+SHOP)>0);
            Shop change=new Shop();change.setId(SHOP);change.setName(RUN+"-rollback");
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(s->{shops.update(change);throw new IllegalStateException("rollback");}));
            assertEquals(RUN,jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=?",String.class,SHOP));
            assertTrue(redis.opsForValue().get("cache:shop:"+SHOP).contains(RUN));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tb_reliable_event WHERE resource_id=?",Integer.class,SHOP));
            record("CACHE_AFTER_COMMIT_ROLLBACK","rollback retains old cache and saves no intent");

            String first=ok("PUT","/shop",map("id",SHOP,"name",RUN+"-saved"),0).path("data").path("eventId").asText();
            assertEquals(2,eventStatus(first)); assertFalse(redis.hasKey("cache:shop:"+SHOP));
            assertEquals(RUN+"-saved",ok("GET","/shop/"+SHOP,null,0).path("data").path("name").asText());
            record("CACHE_DELETE_SUCCESS","DB committed; afterCommit deletes cache in a new transaction");

            StringRedisTemplate unavailableRedis=mock(StringRedisTemplate.class);
            when(unavailableRedis.delete(anyString())).thenThrow(new IllegalStateException("isolated injected Redis failure"));
            ReflectionTestUtils.setField(events,"redis",unavailableRedis);
            String pending=ok("PUT","/shop",map("id",SHOP,"name",RUN+"-compensated"),0).path("data").path("eventId").asText();
            assertEquals(0,eventStatus(pending));assertEquals(RUN+"-compensated",jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=?",String.class,SHOP));
            Map<String,Object> brokerConfig=new HashMap<>();
            brokerConfig.put("bootstrap.servers","127.0.0.1:65530");brokerConfig.put("key.serializer",org.apache.kafka.common.serialization.StringSerializer.class);
            brokerConfig.put("value.serializer",org.apache.kafka.common.serialization.StringSerializer.class);
            brokerConfig.put("max.block.ms",1000);brokerConfig.put("request.timeout.ms",500);brokerConfig.put("delivery.timeout.ms",2000);
            try(java.net.ServerSocket checkPort=new java.net.ServerSocket(65530)) {assertTrue(checkPort.isBound());}
            DefaultKafkaProducerFactory<String,String> unavailableFactory=new DefaultKafkaProducerFactory<>(brokerConfig);
            try {
                ReflectionTestUtils.setField(publisher,"kafka",new KafkaTemplate<>(unavailableFactory));publisher.publish();
            } finally {ReflectionTestUtils.setField(publisher,"kafka",originalKafka);unavailableFactory.destroy();}
            assertEquals(0,eventStatus(pending)); assertTrue(jdbc.queryForObject("SELECT send_attempts FROM tb_reliable_event WHERE id=?",Integer.class,pending)>0);
            record("CACHE_KAFKA_SEND_FAILURE_RETAINED","DB intent remains pending when Kafka send fails");
            ReflectionTestUtils.setField(publisher,"kafka",originalKafka);
            jdbc.update("UPDATE tb_reliable_event SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",pending); publisher.publish();
            await("blocked event",()->eventStatus(pending)==3);
            assertEquals(3,jdbc.queryForObject("SELECT consume_attempts FROM tb_reliable_event WHERE id=?",Integer.class,pending));
            assertEquals(3,ok("GET","/demo/cache-events/"+pending,null,0).path("data").path("status").asInt());
            record("CACHE_CONSUMER_BLOCKED_VISIBLE","3 real Kafka delivery failures quarantined durably; no silent loss");
            ReflectionTestUtils.setField(events,"redis",originalRedis);
            ok("POST","/demo/cache-events/"+pending+"/retry",null,0);publishAndDrain();assertEquals(2,eventStatus(pending));
            assertEquals(RUN+"-compensated",ok("GET","/shop/"+SHOP,null,0).path("data").path("name").asText());
            String cacheAfter=redis.opsForValue().get("cache:shop:"+SHOP);
            long cacheOffset=kafka.send(RUN+"-cache",String.valueOf(SHOP),pending).get(5,TimeUnit.SECONDS).getRecordMetadata().offset();
            await("cache completion offset",()->eventStatus(pending)==2&&committed(RUN+"-cache",cacheOffset));
            assertEquals(cacheAfter,redis.opsForValue().get("cache:shop:"+SHOP));
            record("CACHE_RECOVERY_DUPLICATE_PASS","requeue consumed; duplicate DONE event preserves rebuilt fresh cache");

            lifecycle.close(rollbackOrder,users.get(0),false);
            String stockEvent=jdbc.queryForObject("SELECT id FROM tb_reliable_event WHERE resource_id=?",String.class,rollbackOrder);
            ReliableEventMapper completionFails=mock(ReliableEventMapper.class,org.mockito.AdditionalAnswers.delegatesTo(eventMapper));
            doReturn(0).when(completionFails).completed(stockEvent);
            ReflectionTestUtils.setField(events,"events",completionFails);
            assertThrows(IllegalStateException.class,()->events.process(stockEvent,ReliableEventService.STOCK_RESTORE));
            ReflectionTestUtils.setField(events,"events",originalMapper);
            assertEquals(0,eventStatus(stockEvent));assertEquals(1,redisStock(rollbackVoucher)); // Redis effect survives failed DB completion
            events.process(stockEvent,ReliableEventService.STOCK_RESTORE);assertEquals(1,redisStock(rollbackVoucher));
            long stockOffset=kafka.send(RUN+"-stock",String.valueOf(rollbackOrder),stockEvent).get(5,TimeUnit.SECONDS).getRecordMetadata().offset();
            await("stock completion offset",()->eventStatus(stockEvent)==2&&committed(RUN+"-stock",stockOffset));
            assertEquals(1,stock(rollbackVoucher)); assertEquals(1,redisStock(rollbackVoucher));
            record("RESTORE_LUA_DUPLICATE_PASS","marker prevents second increment even when event completion is replayed");
            long badStockVoucher=voucher(1,1), badStockOrder=submit(badStockVoucher,0);
            redis.delete("seckill:stock:"+badStockVoucher);redis.opsForList().leftPush("seckill:stock:"+badStockVoucher,"invalid-test-type");
            lifecycle.close(badStockOrder,users.get(0),false);
            String blockedStockEvent=jdbc.queryForObject("SELECT id FROM tb_reliable_event WHERE resource_id=?",String.class,badStockOrder);
            publisher.publish();await("stock blocked",()->eventStatus(blockedStockEvent)==3);
            assertEquals(1,stock(badStockVoucher));assertFalse(redis.hasKey(ReliableEventService.RESTORED_KEY+badStockOrder));
            redis.delete("seckill:stock:"+badStockVoucher);redis.opsForValue().set("seckill:stock:"+badStockVoucher,"0");
            assertEquals(1,eventMapper.requeue(blockedStockEvent));publishAndDrain();
            assertEquals(1,redisStock(badStockVoucher));record("STOCK_CONSUMER_FAILURE_RECOVERY","real Lua type failure retained; repaired/requeued once; final inventory matches");
            record("KAFKA_COMPLETION_ACK_PASS","both independent consumer groups committed offsets for completed events");
            ok("GET","/voucher-order/mine",null,0);
            snapshot("FINAL_INVENTORY",rollbackVoucher);
        } finally {
            ReflectionTestUtils.setField(events,"redis",originalRedis);ReflectionTestUtils.setField(events,"events",originalMapper);
            ReflectionTestUtils.setField(publisher,"kafka",originalKafka);
            listeners.getListenerContainers().forEach(c->c.stop());
            if(admin!=null)admin.close();
            cleanup(); assertEquals(before,counts()); record("ISOLATED_CLEANUP_PASS","row counts restored; only owned IDs and keys deleted");
            Path path=Paths.get(System.getProperty("lifecycle.evidence","target/lifecycle-evidence.json"));Files.createDirectories(path.toAbsolutePath().getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),evidence);
        }
    }
    private void login(int index)throws Exception {
        String phone="199"+String.format("%07d",System.currentTimeMillis()%10000000)+index;phones.add(phone);
        request("POST","/user/code?phone="+phone,null,-1);
        String code=redis.opsForValue().get("login:code:"+phone);
        String token=request("POST","/user/login",map("phone",phone,"code",code),-1).path("data").asText();
        assertFalse(token.isEmpty());tokens.add(token);users.add(ok("GET","/user/me",null,index).path("data").path("id").asLong());
    }
    private long voucher(int stock,int type) {
        long id=SHOP+100+vouchers.size();vouchers.add(id);
        jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES(?,?,?,100,500,?,1)",id,SHOP,RUN+"-voucher",type);
        if(type==1){jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(?,?,TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP),TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP))",id,stock);redis.opsForValue().set("seckill:stock:"+id,String.valueOf(stock));}
        return id;
    }
    private long submit(long voucher,int user)throws Exception {
        long id=Long.parseLong(ok("POST","/voucher-order/seckill/"+voucher,null,user).path("data").asText());orderIds.add(id);
        await("order committed",()->jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=?",Integer.class,id)==1);return id;
    }
    private JsonNode request(String method,String path,Object body,int user)throws Exception {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(user>=0)headers.set("authorization",tokens.get(user));
        ResponseEntity<String> result=http.exchange(path,HttpMethod.valueOf(method),new HttpEntity<>(body==null?null:json.writeValueAsString(body),headers),String.class);
        assertEquals(200,result.getStatusCodeValue());return json.readTree(result.getBody());
    }
    private long normalPurchase(long voucher,int user,String requestId)throws Exception {
        HttpHeaders headers=new HttpHeaders();headers.set("authorization",tokens.get(user));headers.set("Idempotency-Key",requestId);
        ResponseEntity<String> response=http.exchange("/voucher-order/normal/"+voucher,HttpMethod.POST,new HttpEntity<>(null,headers),String.class);
        assertEquals(200,response.getStatusCodeValue());JsonNode result=json.readTree(response.getBody());
        assertTrue(result.path("success").asBoolean(),result.path("errorMsg").asText());return Long.parseLong(result.path("data").asText());
    }
    private JsonNode ok(String method,String path,Object body,int user)throws Exception {JsonNode r=request(method,path,body,user);assertTrue(r.path("success").asBoolean(),r.path("errorMsg").asText());return r;}
    private int status(long id){return jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE id=?",Integer.class,id);}
    private int stock(long id){return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",Integer.class,id);}
    private int redisStock(long id){return Integer.parseInt(redis.opsForValue().get("seckill:stock:"+id));}
    private int eventStatus(String id){return jdbc.queryForObject("SELECT status FROM tb_reliable_event WHERE id=?",Integer.class,id);}
    private boolean committed(String topic,long offset){try{OffsetAndMetadata c=admin.listConsumerGroupOffsets(topic).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).get(new TopicPartition(topic,0));return c!=null&&c.offset()>offset;}catch(Exception failure){return false;}}
    private void publishAndDrain()throws Exception {await("events drained",()->{publisher.publish();return jdbc.queryForObject("SELECT COUNT(*) FROM tb_reliable_event WHERE status IN(0,1) AND (resource_id=? OR resource_id IN(SELECT id FROM tb_voucher_order WHERE voucher_id IN(SELECT id FROM tb_voucher WHERE shop_id=?)))",Integer.class,SHOP,SHOP)==0;});}
    private void await(String label,BooleanSupplier check)throws Exception {long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(check.getAsBoolean())return;Thread.sleep(100);}fail(label);}
    private Map<String,Integer> counts(){Map<String,Integer> m=new LinkedHashMap<>();for(String t:Arrays.asList("tb_user","tb_shop","tb_voucher","tb_seckill_voucher","tb_voucher_order","tb_reliable_event"))m.put(t,jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Integer.class));return m;}
    private void snapshot(String name,long voucher){Map<String,Object> m=new LinkedHashMap<>();m.put("test",name);m.put("voucherId",voucher);m.put("mysqlStock",stock(voucher));m.put("redisStock",redisStock(voucher));m.put("orders",jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?",Integer.class,voucher));evidence.add(m);}
    private void record(String name,String detail){evidence.add(map("test",name,"result","PASS","detail",detail));}
    private Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]);return m;}
    private void cleanup(){
        for(Long id:orderIds){jdbc.update("DELETE FROM tb_reliable_event WHERE resource_id=? AND event_type='SECKILL_STOCK_RESTORE'",id);redis.delete(Arrays.asList("seckill:reservation:"+id,"order:stock-restored:"+id));}
        jdbc.update("DELETE FROM tb_reliable_event WHERE resource_id=? AND event_type='SHOP_CACHE_DELETE'",SHOP);
        for(Long id:vouchers){jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?",id);jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?",id);jdbc.update("DELETE FROM tb_voucher WHERE id=?",id);redis.delete(Arrays.asList("seckill:stock:"+id,"seckill:order:"+id));}
        for(int i=0;i<users.size();i++){jdbc.update("DELETE FROM tb_user WHERE id=? AND phone=?",users.get(i),phones.get(i));redis.delete(Arrays.asList("login:token:"+tokens.get(i),"login:code:"+phones.get(i)));}
        jdbc.update("DELETE FROM tb_shop WHERE id=? AND name LIKE ?",SHOP,RUN+"%");redis.delete(Arrays.asList("cache:shop:"+SHOP,"cache:voucher:list:"+SHOP,"loadtest:outbox:"+RUN));
    }
}
