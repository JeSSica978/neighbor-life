package com.hmdp.service;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

import com.hmdp.business.CatalogService;
import com.hmdp.business.SocialService;
import com.hmdp.config.SeckillConstants;
import com.hmdp.entity.ReliableEvent;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.*;
import com.hmdp.service.cache.VoucherListCacheService;
import java.util.*;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

@Slf4j
@Service
public class ReliableEventService {
  public static final String CACHE_DELETE = "SHOP_CACHE_DELETE";
  public static final String STOCK_RESTORE = "SECKILL_STOCK_RESTORE";
  public static final String RESTORED_KEY = "order:stock-restored:";
  @Resource private ReliableEventMapper events;
  @Resource private VoucherOrderMapper orders;
  @Resource private VoucherMapper vouchers;
  @Resource private StringRedisTemplate redis;
  @Resource private VoucherListCacheService voucherCache;
  @Resource private PlatformTransactionManager transactions;
  @Resource private JdbcTemplate jdbc;
  @Resource private com.hmdp.business.BusinessSchema schema;
  @Resource @org.springframework.context.annotation.Lazy private CatalogService catalog;
  @Resource @org.springframework.context.annotation.Lazy private SocialService social;
  private boolean available;

  @Value("${hmdp.reliable.max-consume-attempts:8}")
  private int maxAttempts;

  private static final DefaultRedisScript<Long> RESTORE = new DefaultRedisScript<>();

  static {
    RESTORE.setLocation(new ClassPathResource("restore-seckill-stock.lua"));
    RESTORE.setResultType(Long.class);
  }

  @PostConstruct
  public void verifySchema() {
    try {
      available =
          jdbc.queryForObject(
                      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                          + "AND table_name='tb_reliable_event'",
                      Integer.class)
                  == 1
              && jdbc.queryForObject(
                      "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                          + "AND table_name='tb_voucher_order' AND index_name='idx_order_status_create'",
                      Integer.class)
                  == 2;
    } catch (Exception failure) {
      available = false;
    }
    if (!available)
      log.warn(
          "ORDER_LIFECYCLE_SCHEMA_REQUIRED featuresDisabled=true migration=phase4-order-lifecycle-and-reliable-events.sql");
  }

  public boolean isAvailable() {
    return available;
  }

  public String create(String type, Long resourceId) {
    if (!available) throw new IllegalStateException("Order lifecycle migration is required");
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Reliable intent must share the business transaction");
    ReliableEvent event = new ReliableEvent();
    event.setId(UUID.randomUUID().toString());
    event.setEventType(type);
    event.setResourceId(resourceId);
    event.setStatus(0);
    event.setSendAttempts(0);
    event.setConsumeAttempts(0);
    // 使用数据库默认时间安排投递，避免 JVM 与 MySQL 时区不同导致任务提前或延后。
    if (events.insert(event) != 1) throw new IllegalStateException("Reliable event insert failed");
    return event.getId();
  }

  public void deleteCacheAfterCommit(String eventId) {
    afterCommit(eventId, CACHE_DELETE);
  }

  public void afterCommit(String eventId, String type) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              process(eventId, type);
            } catch (Exception failure) {
              // 业务事务已经提交，不能在这里假装回滚；持久事件仍待处理，发布者会重投。
              log.warn(
                  "CACHE_DELETE_QUEUED eventId={} errorType={}",
                  eventId,
                  failure.getClass().getSimpleName());
            }
          }
        });
  }

  /**
   * afterCommit 时原事务已经提交，但连接资源可能仍绑定在线程上；必须另开事务保存处理结果。
   * Redis 无法参加 MySQL 事务，因此每次 Redis 操作都需幂等，提交失败后的重投才能安全。
   */
  public void process(String id, String expectedType) {
    UUID.fromString(id);
    TransactionTemplate tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.execute(
        status -> {
          ReliableEvent event = events.lock(id);
          if (event == null
              || (!expectedType.equals(event.getEventType())
                  && !("CACHE_FAMILY".equals(expectedType) && cacheType(event.getEventType()))))
            throw new IllegalArgumentException("Unknown or mismatched reliable event");
          String type = event.getEventType();
          if (event.getStatus() == 2 || event.getStatus() == 3) return null;
          if (CACHE_DELETE.equals(type) || "CATALOG_SYNC".equals(type)) {
            catalog.sync(event.getResourceId(), id);
          } else if (Arrays.asList("BLOG_SYNC", "FOLLOW_SYNC", "BLOG_FEED").contains(type)) {
            social.sync(type, event.getResourceId());
          } else if (STOCK_RESTORE.equals(type)) {
            VoucherOrder order = orders.selectById(event.getResourceId());
            if (order == null
                || (!Integer.valueOf(4).equals(order.getStatus())
                    && !Integer.valueOf(6).equals(order.getStatus())))
              throw new IllegalStateException("Restoration requires a committed cancelled order");
            Long result =
                redis.execute(
                    RESTORE,
                    Arrays.asList(
                        SECKILL_STOCK_KEY + order.getVoucherId(),
                        RESTORED_KEY + order.getId(),
                        SeckillConstants.RESERVATION + order.getId()),
                    order.getUserId().toString(),
                    order.getVoucherId().toString(),
                    Integer.valueOf(6).equals(order.getStatus()) ? "REFUNDED" : "CANCELLED");
            if (result == null || (result != 0 && result != 1))
              throw new IllegalStateException("Redis restoration invariant failure");
            Voucher voucher = vouchers.selectById(order.getVoucherId());
            if (voucher != null) voucherCache.invalidate(voucher.getShopId());
            log.info("ORDER_REDIS_RESTORED orderId={} luaResult={}", order.getId(), result);
          } else throw new IllegalArgumentException("Unsupported event type");
          if (events.completed(id) != 1) throw new IllegalStateException("Event completion failed");
          log.info("RELIABLE_EVENT_DONE eventId={} type={}", id, expectedType);
          return null;
        });
  }

  /** 在失败事务之外记录失败次数，否则错误记录会随业务回滚一起消失。记录失败时继续抛错。 */
  public boolean recordFailure(String id, String type, Exception failure) {
    ReliableEvent event = events.selectById(id);
    if (event == null
        || (!type.equals(event.getEventType())
            && !("CACHE_FAMILY".equals(type) && cacheType(event.getEventType())))) return false;
    events.consumeFailed(id, Math.max(1, maxAttempts), failure.getClass().getSimpleName());
    event = events.selectById(id);
    if (event.getStatus() == 3) {
      log.error(
          "RELIABLE_EVENT_BLOCKED eventId={} type={} attempts={} repairRequired=true",
          id,
          type,
          event.getConsumeAttempts());
      return true; // 阻塞记录已持久保存才允许确认 Kafka；管理员修复原因后重投，消息没有被丢弃。
    }
    return event.getStatus() == 2;
  }

  public static boolean cacheType(String type) {
    return Arrays.asList(CACHE_DELETE, "CATALOG_SYNC", "BLOG_SYNC", "FOLLOW_SYNC", "BLOG_FEED")
        .contains(type);
  }
}
