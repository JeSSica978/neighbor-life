package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.business.BusinessStore;
import com.hmdp.business.CommerceService;
import com.hmdp.config.SeckillConstants;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.OrderLifecycleService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
    implements IVoucherOrderService {

  @Resource private SeckillVoucherMapper seckillVoucherMapper;
  @Resource private VoucherMapper voucherMapper;
  @Resource private RedisIdWorker redisIdWorker;
  @Resource private StringRedisTemplate stringRedisTemplate;

  @Value("${hmdp.seckill.outbox:seckill:outbox}")
  private String outbox;

  @Resource private OrderLifecycleService lifecycle;

  @Value("${hmdp.order.simulated-payment-enabled:true}")
  private boolean simulatedPaymentEnabled;

  @Resource private JdbcTemplate jdbc;
  @Resource private CommerceService commerce;
  @Resource private ObjectMapper objectMapper;
  private boolean normalRepurchaseEnabled;

  @PostConstruct
  public void verifyNormalRepurchaseSchema() {
    try {
      normalRepurchaseEnabled =
          jdbc.queryForObject(
                      "SELECT COUNT(*) FROM information_schema.columns "
                          + "WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' "
                          + "AND column_name IN ('request_id','seckill_voucher_id')",
                      Integer.class)
                  == 2
              && jdbc.queryForObject(
                      "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                          + "WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND non_unique=0 "
                          + "AND index_name IN ('uk_voucher_order_request','uk_voucher_order_seckill')",
                      Integer.class)
                  == 2
              && jdbc.queryForObject(
                      "SELECT COUNT(*) FROM information_schema.statistics "
                          + "WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' "
                          + "AND index_name='uk_voucher_order_user_voucher'",
                      Integer.class)
                  == 0;
    } catch (Exception failure) {
      normalRepurchaseEnabled = false;
    }
    if (!normalRepurchaseEnabled)
      log.warn("NORMAL_REPURCHASE_SCHEMA_REQUIRED migration=phase5-normal-voucher-repurchase.sql");
  }

  private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

  static {
    SECKILL_SCRIPT = new DefaultRedisScript<>();
    SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    SECKILL_SCRIPT.setResultType(Long.class);
  }

  @Override
  public Result seckillVoucher(Long voucherId) {
    if (voucherId == null || voucherId <= 0) return Result.fail("优惠券id无效");
    Voucher voucher = voucherMapper.selectById(voucherId);
    SeckillVoucher sale = seckillVoucherMapper.selectById(voucherId);
    if (voucher == null
        || sale == null
        || !Integer.valueOf(1).equals(voucher.getType())
        || !Integer.valueOf(1).equals(voucher.getStatus())) return Result.fail("秒杀优惠券不可用");
    LocalDateTime now = LocalDateTime.now();
    if (sale.getBeginTime() == null || sale.getEndTime() == null) return Result.fail("秒杀时间未配置");
    if (now.isBefore(sale.getBeginTime())) return Result.fail("秒杀尚未开始");
    if (now.isAfter(sale.getEndTime())) return Result.fail("秒杀已结束");
    Long userId = UserHolder.getUser().getId();
    Map<String, Object> snapshot = commerce.offerSnapshot(voucher);
    String snapshotJson;
    try {
      snapshotJson = objectMapper.writeValueAsString(snapshot);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot encode offer snapshot", failure);
    }
    long orderId = redisIdWorker.nextId("order");
    // 1.执行lua脚本
    Long result =
        stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Arrays.asList(
                SECKILL_STOCK_KEY + voucherId,
                SeckillConstants.ORDER_SET + voucherId,
                outbox,
                SeckillConstants.RESERVATION + orderId,
                "seckill:request:" + voucherId),
            voucherId.toString(),
            userId.toString(),
            String.valueOf(orderId),
            snapshotJson);
    if (result == null) throw new IllegalStateException("Redis Lua did not return a result");
    log.info(
        "SECKILL_LUA voucherId={} userId={} orderId={} result={}",
        voucherId,
        userId,
        orderId,
        result);
    // 2.判断结果
    if (result == 1) {
      return Result.fail("库存不足");
    }
    if (result == 2) {
      Object previous =
          stringRedisTemplate.opsForHash().get("seckill:request:" + voucherId, userId.toString());
      return previous == null ? Result.fail("重复下单") : Result.ok(previous.toString());
    }
    if (result == 3) return Result.fail("秒杀库存未初始化");
    if (result != 0)
      throw new IllegalStateException("Redis seckill data type/invariant error: " + result);
    // Lua also wrote the persistent outbox. This response means accepted, not paid or committed in
    // MySQL.
    return Result.ok(String.valueOf(orderId));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Result purchaseNormalVoucher(Long voucherId, String requestId) {
    if (voucherId == null || voucherId <= 0) return Result.fail("优惠券id无效");
    if (!normalRepurchaseEnabled) return Result.fail("普通券多次购买尚未完成数据库迁移");
    if (requestId == null) requestId = UUID.randomUUID().toString();
    if (!requestId.matches("[A-Za-z0-9_-]{8,64}")) return Result.fail("购买请求编号无效");
    Long userId = UserHolder.getUser().getId();
    // 先锁购买者，再锁商品：同一请求号同时用于两张普通券时也能得到原结果/冲突。
    jdbc.queryForList("SELECT id FROM tb_user WHERE id=? FOR UPDATE", userId);
    // Lock the offer while validating it and serialize retries for this voucher.
    Voucher voucher = voucherMapper.selectForUpdate(voucherId);
    VoucherOrder existing = baseMapper.normalRequest(userId, requestId);
    if (existing != null) {
      if (!Objects.equals(existing.getVoucherId(), voucherId)) return Result.fail("购买请求编号已用于其他优惠券");
      return Result.ok(String.valueOf(existing.getId()));
    }
    if (voucher == null
        || !Integer.valueOf(0).equals(voucher.getType())
        || !Integer.valueOf(1).equals(voucher.getStatus())) return Result.fail("普通优惠券不可用");
    if (voucher.getPayValue() == null
        || voucher.getPayValue() <= 0
        || voucher.getActualValue() == null
        || voucher.getActualValue() <= 0) return Result.fail("优惠券金额未正确配置");
    VoucherOrder order = new VoucherOrder();
    order.setId(redisIdWorker.nextId("order"));
    order.setUserId(userId);
    order.setVoucherId(voucherId);
    order.setStatus(1);
    if (baseMapper.insertNormal(order, requestId) != 1)
      throw new IllegalStateException("Normal voucher order insert failed");
    commerce.attach(order, voucher);
    log.info(
        "NORMAL_ORDER_INSERT orderId={} userId={} voucherId={} transactionActive=true",
        order.getId(),
        userId,
        voucherId);
    // The Spring proxy commits before the controller receives this result. No payment is performed.
    return Result.ok(String.valueOf(order.getId()));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void createVoucherOrder(VoucherOrder voucherOrder) {
    Long userId = voucherOrder.getUserId();
    Long voucherId = voucherOrder.getVoucherId();
    // Serialize admissions for this voucher using an InnoDB row lock, released at transaction end.
    SeckillVoucher sale = seckillVoucherMapper.selectForUpdate(voucherId);
    if (sale == null) throw new IllegalStateException("Missing seckill voucher " + voucherId);
    VoucherOrder existing = getById(voucherOrder.getId());
    if (existing != null) {
      if (!Objects.equals(existing.getUserId(), userId)
          || !Objects.equals(existing.getVoucherId(), voucherId)) {
        throw new IllegalStateException("Order id collision " + voucherOrder.getId());
      }
      log.info("SECKILL_DB_DUPLICATE orderId={} stockUnchanged=true", voucherOrder.getId());
      return;
    }
    String reservation = SeckillConstants.RESERVATION + voucherOrder.getId();
    if (!String.valueOf(userId).equals(stringRedisTemplate.opsForHash().get(reservation, "userId"))
        || !String.valueOf(voucherId)
            .equals(stringRedisTemplate.opsForHash().get(reservation, "voucherId"))) {
      throw new IllegalStateException(
          "Missing or mismatched Redis reservation " + voucherOrder.getId());
    }
    if (query().eq("user_id", userId).eq("voucher_id", voucherId).count() > 0) {
      throw new IllegalStateException(
          "User/voucher already has a different order; reservation requires reconciliation");
    }
    int changed =
        seckillVoucherMapper.update(
            null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<SeckillVoucher>()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0));
    if (changed != 1)
      throw new IllegalStateException(
          "MySQL stock mismatch; keep reservation for retry/reconciliation");
    voucherOrder.setStatus(1);
    if (normalRepurchaseEnabled ? baseMapper.insertSeckill(voucherOrder) != 1 : !save(voucherOrder))
      throw new IllegalStateException("Order insert failed");
    commerce.attach(voucherOrder, null);
    log.info(
        "SECKILL_DB_INSERT orderId={} userId={} voucherId={} transactionActive=true",
        voucherOrder.getId(),
        userId,
        voucherId);
  }

  @Override
  public Result queryOrderStatus(Long orderId) {
    Long userId = UserHolder.getUser().getId();
    VoucherOrder order = getById(orderId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", String.valueOf(orderId));
    if (order != null && Objects.equals(order.getUserId(), userId)) {
      if (expired(order)) {
        lifecycle.close(orderId, userId, true);
        order = getById(orderId);
      }
      return Result.ok(orderView(order));
    }
    String reservation = SeckillConstants.RESERVATION + orderId;
    if (order == null
        && String.valueOf(userId)
            .equals(stringRedisTemplate.opsForHash().get(reservation, "userId"))) {
      result.put("state", "PENDING");
      return Result.ok(result);
    }
    return Result.fail("订单不存在或无权查看");
  }

  @Override
  public Result listMyOrders(int page) {
    if (page < 1 || page > 10000) return Result.fail("分页参数不正确");
    Long userId = UserHolder.getUser().getId();
    Page<VoucherOrder> rows =
        query().eq("user_id", userId).orderByDesc("create_time", "id").page(new Page<>(page, 10));
    List<Map<String, Object>> views = new ArrayList<>();
    for (VoucherOrder order : rows.getRecords()) {
      if (expired(order)) {
        lifecycle.close(order.getId(), userId, true);
        order = getById(order.getId());
      }
      views.add(orderView(order));
    }
    return Result.ok(views, rows.getTotal());
  }

  @Override
  public Result simulatePayment(Long orderId) {
    if (!lifecycle.isAvailable()) return Result.fail("订单生命周期功能尚未完成数据库迁移");
    if (!simulatedPaymentEnabled) return Result.fail("模拟支付未启用");
    Long userId = UserHolder.getUser().getId();
    VoucherOrder order = lifecycle.pay(orderId, userId);
    if (order == null) return Result.fail("订单不存在或无权操作");
    if (Integer.valueOf(2).equals(order.getStatus())) return Result.ok(orderView(order));
    if (Integer.valueOf(1).equals(order.getStatus())) lifecycle.close(orderId, userId, true);
    return Result.fail("订单已取消或超过支付期限，无法支付");
  }

  @Override
  public Result cancelOrder(Long orderId) {
    if (!lifecycle.isAvailable()) return Result.fail("订单生命周期功能尚未完成数据库迁移");
    Long userId = UserHolder.getUser().getId();
    VoucherOrder order = getById(orderId);
    if (order == null || !Objects.equals(order.getUserId(), userId))
      return Result.fail("订单不存在或无权操作");
    if (!commerce.managed(orderId))
      throw new com.hmdp.business.BusinessException(409, "HISTORICAL_ORDER", "历史订单保留只读，请购买新的优惠券");
    lifecycle.close(orderId, userId, false);
    order = getById(orderId);
    if (!Integer.valueOf(4).equals(order.getStatus())) return Result.fail("订单已支付或已核销，无法取消");
    return Result.ok(orderView(order));
  }

  private boolean expired(VoucherOrder order) {
    Map<String, Object> detail = commerce.detail(order.getId());
    return lifecycle.isAvailable()
        && detail != null
        && Integer.valueOf(1).equals(order.getStatus())
        && BusinessStore.number(detail, "remaining_seconds") == 0;
  }

  private Map<String, Object> orderView(VoucherOrder order) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", order.getId().toString());
    view.put("state", "CREATED");
    view.put("voucherId", order.getVoucherId().toString());
    view.put("status", order.getStatus());
    view.put("createTime", order.getCreateTime());
    view.put("payTime", order.getPayTime());
    view.put("simulatedPayment", Integer.valueOf(0).equals(order.getPayType()));
    view.put("lifecycleEnabled", lifecycle.isAvailable());
    view.put(
        "canPay",
        commerce.managed(order.getId())
            && lifecycle.isAvailable()
            && simulatedPaymentEnabled
            && Integer.valueOf(1).equals(order.getStatus()));
    Long seconds = baseMapper.remainingSeconds(order.getId(), lifecycle.timeoutSeconds());
    long remaining = seconds == null ? 0 : seconds;
    view.put("remainingSeconds", remaining);
    if (order.getCreateTime() != null)
      view.put("expiresAt", order.getCreateTime().plusSeconds(lifecycle.timeoutSeconds()));
    Voucher voucher = voucherMapper.selectById(order.getVoucherId());
    if (voucher != null) {
      view.put("title", voucher.getTitle());
      view.put("shopId", voucher.getShopId().toString());
      view.put("payValue", voucher.getPayValue());
      view.put("type", voucher.getType());
    }
    Map<String, Object> detail = commerce.detail(order.getId());
    view.put("historical", detail == null);
    view.put("canCancel", detail != null && Integer.valueOf(1).equals(order.getStatus()));
    if (detail != null) {
      view.put("title", detail.get("title"));
      view.put("shopId", detail.get("shop_id").toString());
      view.put("payValue", detail.get("pay_value"));
      view.put("actualValue", detail.get("actual_value"));
      view.put("rules", detail.get("rules"));
      view.put("type", detail.get("voucher_type"));
      view.put("remainingSeconds", BusinessStore.number(detail, "remaining_seconds"));
      view.put("expiresAt", ((java.sql.Timestamp) detail.get("expires_at")).toInstant().toString());
      view.put("closeReason", detail.get("close_reason"));
      view.put(
          "canRefund",
          Integer.valueOf(2).equals(order.getStatus())
              && BusinessStore.number(detail, "refund_allowed") == 1);
    }
    return view;
  }
}
