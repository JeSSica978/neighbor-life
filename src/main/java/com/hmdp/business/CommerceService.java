package com.hmdp.business;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.SeckillConstants;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ReliableEventService;
import java.util.*;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 交易状态只由数据库事务推进。Redis 负责秒杀预占和异步恢复，不能用它判定支付成功。
 * 新订单拥有 detail 快照；没有快照的历史订单保留只读，不自动制造支付或返库。
 * 订单状态：1 待支付 → 2 已支付 → 3 已核销；1 → 4 已取消；2 → 5 退款中 → 6 已退款。
 * 支付流水记录渠道结果，订单记录业务结果；晚到支付成功时，订单仍可保持已取消。
 */
@Service
public class CommerceService {
  @Resource private BusinessStore db;
  @Resource private BusinessSchema schema;
  @Resource private AccessService access;
  @Resource private VoucherOrderMapper orders;
  @Resource private SeckillVoucherMapper sales;
  @Resource private ReliableEventService events;
  @Resource private PlatformTransactionManager transactions;
  @Resource private StringRedisTemplate redis;
  @Resource private ObjectMapper json;

  @Value("${hmdp.order.payment-timeout-seconds:900}")
  private long timeout;

  @Value("${hmdp.order.simulated-payment-enabled:true}")
  private boolean mock;

  private <T> T tx(java.util.function.Supplier<T> task) {
    return new TransactionTemplate(transactions).execute(s -> task.get());
  }

  public Map<String, Object> offerSnapshot(Voucher voucher) {
    schema.require();
    Map<String, Object> p =
        db.one("SELECT * FROM tb_voucher_policy WHERE voucher_id=?", voucher.getId());
    if (db.count(
            "SELECT COUNT(*) FROM tb_shop_settings WHERE shop_id=? AND status<>1",
            voucher.getShopId())
        > 0) throw new BusinessException(409, "SHOP_UNAVAILABLE", "店铺已下架");
    if (p != null && BusinessStore.number(p, "ready") != 1)
      throw new BusinessException(409, "OFFER_PREPARING", "优惠券正在准备，请稍后");
    if (p != null
        && p.get("valid_until") != null
        && ((java.sql.Timestamp) p.get("valid_until")).getTime() <= System.currentTimeMillis())
      throw new BusinessException(409, "OFFER_EXPIRED", "优惠券使用有效期已结束");
    if (voucher.getPayValue() == null
        || voucher.getPayValue() <= 0
        || voucher.getActualValue() == null
        || voucher.getActualValue() <= 0)
      throw new BusinessException(409, "INVALID_OFFER", "优惠券金额未正确配置");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("shopId", voucher.getShopId());
    out.put("type", voucher.getType());
    out.put("title", voucher.getTitle());
    out.put("rules", voucher.getRules() == null ? "" : voucher.getRules());
    out.put("payValue", voucher.getPayValue());
    out.put("actualValue", voucher.getActualValue());
    out.put("validDays", p == null ? null : p.get("valid_days"));
    out.put(
        "validUntilEpoch",
        p == null || p.get("valid_until") == null
            ? null
            : ((java.sql.Timestamp) p.get("valid_until")).getTime() / 1000);
    out.put("minSpend", p == null ? 0 : p.get("min_spend"));
    out.put("refundAllowed", p == null ? 1 : p.get("refund_allowed"));
    out.put("acceptedEpoch", db.count("SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP)"));
    return out;
  }
  // 与订单 INSERT 共用事务；秒杀使用 Lua 受理时冻结的快照，避免消费延迟期间商品改价。
  public void attach(VoucherOrder order, Voucher offer) {
    schema.require();
    Map<String, Object> snap;
    if (offer != null) snap = offerSnapshot(offer);
    else {
      Object raw = redis.opsForHash().get(SeckillConstants.RESERVATION + order.getId(), "snapshot");
      if (raw == null) throw new IllegalStateException("Missing accepted offer snapshot");
      try {
        snap = json.readValue(raw.toString(), new TypeReference<Map<String, Object>>() {});
      } catch (Exception e) {
        throw new IllegalStateException("Invalid reservation snapshot", e);
      }
    }
    db.jdbc.update(
        "INSERT INTO tb_order_detail(order_id,shop_id,voucher_type,title,rules,pay_value,actual_value,valid_days,valid_until,min_spend,refund_allowed,expires_at) VALUES(?,?,?,?,?,?,?,?,FROM_UNIXTIME(?),?,?,FROM_UNIXTIME(?))",
        order.getId(),
        snap.get("shopId"),
        snap.get("type"),
        snap.get("title"),
        snap.get("rules"),
        snap.get("payValue"),
        snap.get("actualValue"),
        snap.get("validDays"),
        snap.get("validUntilEpoch"),
        snap.get("minSpend"),
        snap.get("refundAllowed"),
        BusinessStore.number(snap, "acceptedEpoch") + Math.max(1, timeout));
    if (BusinessStore.number(snap, "type") == 1)
      db.jdbc.update(
          "INSERT INTO tb_stock_ledger(order_id,kind,voucher_id) VALUES(?,'RESERVE',?)",
          order.getId(),
          order.getVoucherId());
  }

  public boolean managed(long order) {
    return schema.ready()
        && db.count("SELECT COUNT(*) FROM tb_order_detail WHERE order_id=?", order) == 1;
  }

  public Map<String, Object> detail(long order) {
    return schema.ready()
        ? db.one(
            "SELECT *,GREATEST(TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,expires_at),0) remaining_seconds FROM tb_order_detail WHERE order_id=?",
            order)
        : null;
  }
  // 所有交易支路遵守同一锁顺序：秒杀库存行 → 订单行 → 支付/持有券/退款行。
  // 退款和核销会争用同一订单锁，只有先取得锁并满足原状态的一方能够推进。
  private Map<String, Object> lock(long order, Long user) {
    Map<String, Object> d = db.one("SELECT * FROM tb_order_detail WHERE order_id=?", order);
    if (d == null) throw new BusinessException(409, "HISTORICAL_ORDER", "历史订单保留只读，请购买新的优惠券");
    if (BusinessStore.number(d, "voucher_type") == 1)
      sales.selectForUpdate(
          BusinessStore.number(
              db.one("SELECT voucher_id FROM tb_voucher_order WHERE id=?", order), "voucher_id"));
    VoucherOrder o = orders.current(order);
    if (o == null || (user != null && !user.equals(o.getUserId())))
      throw new BusinessException(404, "ORDER_NOT_FOUND", "订单不存在或无权操作");
    d.put("user_id", o.getUserId());
    d.put("voucher_id", o.getVoucherId());
    d.put("status", o.getStatus());
    return d;
  }

  public Map<String, Object> createPayment(long order, long user, String request) {
    schema.require();
    if (!mock) throw new BusinessException(403, "MOCK_DISABLED", "本地模拟支付未启用");
    String key = BusinessStore.key(request);
    return tx(
        () -> {
          Map<String, Object> d = lock(order, user);
          Map<String, Object> old =
              db.one("SELECT * FROM tb_payment WHERE user_id=? AND request_id=?", user, key);
          if (old != null && BusinessStore.number(old, "order_id") != order)
            throw new BusinessException(409, "KEY_CONFLICT", "请求编号已用于其他订单");
          Map<String, Object> pay = db.one("SELECT * FROM tb_payment WHERE order_id=?", order);
          if (pay != null) return paymentView(pay, user);
          if (BusinessStore.number(d, "status") != 1
              || db.count(
                      "SELECT COUNT(*) FROM tb_order_detail WHERE order_id=? AND expires_at>CURRENT_TIMESTAMP",
                      order)
                  != 1) throw new BusinessException(409, "ORDER_NOT_PAYABLE", "订单已关闭或超过支付期限");
          String id = UUID.randomUUID().toString();
          db.jdbc.update(
              "INSERT INTO tb_payment(id,order_id,user_id,request_id,amount) VALUES(?,?,?,?,?)",
              id,
              order,
              user,
              key,
              d.get("pay_value"));
          return payment(id, user);
        });
  }

  private Map<String, Object> paymentView(Map<String, Object> p, long user) {
    if (p == null || BusinessStore.number(p, "user_id") != user)
      throw new BusinessException(404, "PAYMENT_NOT_FOUND", "支付单不存在或无权查看");
    Map<String, Object> v = BusinessStore.view(p);
    v.put("simulated", true);
    return v;
  }

  public Map<String, Object> payment(String id, long user) {
    schema.require();
    return paymentView(db.one("SELECT * FROM tb_payment WHERE id=?", id), user);
  }

  public Map<String, Object> confirm(
      String id, long user, String request, String result, Long amount) {
    schema.require();
    if (!mock) throw new BusinessException(403, "MOCK_DISABLED", "模拟渠道未启用");
    if (!Arrays.asList("SUCCESS", "FAILED", "CANCELLED").contains(result))
      throw new BusinessException(400, "INVALID_RESULT", "模拟支付结果不正确");
    String key = BusinessStore.key(request);
    // 先单独提交通知，再推进订单。后续发券事务即使失败，通知仍在，恢复任务可重新处理。
    // 这只是绑定当前登录用户和支付单的本地模拟入口，不是可以接收真实资金的支付回调。
    tx(
        () -> {
          Map<String, Object> p = db.one("SELECT * FROM tb_payment WHERE id=? FOR UPDATE", id);
          paymentView(p, user);
          if (amount != null && amount.longValue() != BusinessStore.number(p, "amount"))
            throw new BusinessException(400, "AMOUNT_MISMATCH", "支付金额与订单不一致");
          Map<String, Object> existing = db.one("SELECT * FROM tb_payment_notice WHERE id=?", key);
          if (existing != null) {
            if (!id.equals(existing.get("payment_id")) || !result.equals(existing.get("result")))
              throw new BusinessException(409, "KEY_CONFLICT", "通知编号已用于其他结果");
            return null;
          }
          db.jdbc.update(
              "INSERT INTO tb_payment_notice(id,payment_id,result,amount) VALUES(?,?,?,?)",
              key,
              id,
              result,
              p.get("amount"));
          return null;
        });
    processNotice(key);
    drainRefunds();
    return payment(id, user);
  }

  public void processNotice(String key) {
    tx(
        () -> {
          Map<String, Object> n = db.one("SELECT * FROM tb_payment_notice WHERE id=?", key);
          if (n == null || BusinessStore.number(n, "state") != 0) return null;
          Map<String, Object> p =
              db.one("SELECT * FROM tb_payment WHERE id=?", n.get("payment_id"));
          if (p == null) throw new IllegalStateException("Unknown payment notice");
          long id = BusinessStore.number(p, "order_id");
          Map<String, Object> d = lock(id, null);
          n = db.one("SELECT * FROM tb_payment_notice WHERE id=? FOR UPDATE", key);
          if (BusinessStore.number(n, "state") != 0) return null;
          p = db.one("SELECT * FROM tb_payment WHERE id=? FOR UPDATE", p.get("id"));
          if (BusinessStore.number(n, "amount") != BusinessStore.number(p, "amount"))
            throw new IllegalStateException("Notice amount invariant");
          if ("SUCCESS".equals(n.get("result"))) {
            if (!"SUCCESS".equals(p.get("status"))) {
              db.jdbc.update(
                  "UPDATE tb_payment SET status='SUCCESS',pay_time=CURRENT_TIMESTAMP WHERE id=?",
                  p.get("id"));
              int changed =
                  db.jdbc.update(
                      "UPDATE tb_voucher_order o JOIN tb_order_detail d ON d.order_id=o.id SET o.status=2,o.pay_type=0,o.pay_time=CURRENT_TIMESTAMP WHERE o.id=? AND o.status=1 AND d.expires_at>CURRENT_TIMESTAMP",
                      id);
              // CAS 同时校验状态和数据库时间。通知成功并不等于订单仍可支付。
              if (changed == 1) {
                // 支付成功、订单已支付和持有券 INSERT 在同一事务；发券失败会一起回滚。
                String code =
                    UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", "");
                db.jdbc.update(
                    "INSERT INTO tb_user_voucher(id,order_id,user_id,shop_id,code,valid_until) VALUES(?,?,?,?,?,CASE WHEN ? IS NOT NULL THEN ? WHEN ? IS NOT NULL THEN TIMESTAMPADD(DAY,?,CURRENT_TIMESTAMP) ELSE NULL END)",
                    UUID.randomUUID().toString(),
                    id,
                    p.get("user_id"),
                    d.get("shop_id"),
                    code,
                    d.get("valid_until"),
                    d.get("valid_until"),
                    d.get("valid_days"),
                    d.get("valid_days"));
              } else {
                long status = BusinessStore.number(d, "status");
                if (status == 1) {
                  closeLocked(id, d, "TIMEOUT");
                  lateRefund(p);
                } else if (status == 4) lateRefund(p);
                else throw new IllegalStateException("Payment/order state invariant");
                // 关单先赢：款项做模拟退款，已释放的库存不再释放，也不补发券。
              }
            }
          } else if (!"SUCCESS".equals(p.get("status")))
            db.jdbc.update(
                "UPDATE tb_payment SET status=? WHERE id=?", n.get("result"), p.get("id"));
          db.jdbc.update("UPDATE tb_payment_notice SET state=2,last_error=NULL WHERE id=?", key);
          return null;
        });
  }

  private void lateRefund(Map<String, Object> pay) {
    if (db.count("SELECT COUNT(*) FROM tb_refund WHERE payment_id=?", pay.get("id")) == 0)
      db.jdbc.update(
          "INSERT INTO tb_refund(id,payment_id,order_id,user_id,request_id,amount,reason) VALUES(?,?,?,?,?,?,'LATE_PAYMENT')",
          UUID.randomUUID().toString(),
          pay.get("id"),
          pay.get("order_id"),
          pay.get("user_id"),
          UUID.randomUUID().toString(),
          pay.get("amount"));
  }

  public VoucherOrder payNow(long id, long user) {
    Map<String, Object> p = createPayment(id, user, UUID.randomUUID().toString());
    confirm(p.get("id").toString(), user, UUID.randomUUID().toString(), "SUCCESS", null);
    return orders.selectById(id);
  }

  public boolean close(long id, Long user, boolean expiredOnly) {
    schema.require();
    return tx(
        () -> {
          Map<String, Object> d = lock(id, user);
          if (expiredOnly
              && db.count(
                      "SELECT COUNT(*) FROM tb_order_detail WHERE order_id=? AND expires_at<=CURRENT_TIMESTAMP",
                      id)
                  == 0) return false;
          return closeLocked(id, d, expiredOnly ? "TIMEOUT" : "USER");
        });
  }

  private boolean closeLocked(long id, Map<String, Object> d, String reason) {
    if (db.jdbc.update(
            "UPDATE tb_voucher_order SET status=4,update_time=CURRENT_TIMESTAMP WHERE id=? AND status=1",
            id)
        != 1) return false;
    db.jdbc.update("UPDATE tb_order_detail SET close_reason=? WHERE order_id=?", reason, id);
    release(id, d, reason);
    return true;
  }
  // RELEASE 唯一账目与 DB 加库存、可靠事件同事务提交；Kafka 重投再由 Lua 去重。
  private void release(long id, Map<String, Object> d, String reason) {
    if (BusinessStore.number(d, "voucher_type") != 1) return;
    int inserted =
        db.jdbc.update(
            "INSERT IGNORE INTO tb_stock_ledger(order_id,kind,voucher_id,reason) VALUES(?,'RELEASE',?,?)",
            id,
            d.get("voucher_id"),
            reason);
    if (inserted == 1) {
      if (db.jdbc.update(
              "UPDATE tb_seckill_voucher SET stock=stock+1 WHERE voucher_id=?", d.get("voucher_id"))
          != 1) throw new IllegalStateException("Missing reserved stock");
      events.create(ReliableEventService.STOCK_RESTORE, id);
    }
  }

  public Map<String, Object> refund(long order, long user, String request) {
    schema.require();
    String key = BusinessStore.key(request);
    Map<String, Object> r =
        tx(
            () -> {
              Map<String, Object> d = lock(order, user);
              Map<String, Object> prior =
                  db.one("SELECT * FROM tb_refund WHERE user_id=? AND request_id=?", user, key);
              if (prior != null && BusinessStore.number(prior, "order_id") != order)
                throw new BusinessException(409, "KEY_CONFLICT", "退款编号已用于其他订单");
              Map<String, Object> old = db.one("SELECT * FROM tb_refund WHERE order_id=?", order);
              if (old != null) return BusinessStore.view(old);
              if (BusinessStore.number(d, "status") != 2
                  || BusinessStore.number(d, "refund_allowed") != 1)
                throw new BusinessException(409, "NOT_REFUNDABLE", "订单已核销或不符合退款规则");
              Map<String, Object> v =
                  db.one("SELECT * FROM tb_user_voucher WHERE order_id=? FOR UPDATE", order);
              if (v == null || !"UNUSED".equals(v.get("status")))
                throw new BusinessException(409, "NOT_REFUNDABLE", "优惠券已使用或正在退款");
              Map<String, Object> p =
                  db.one("SELECT * FROM tb_payment WHERE order_id=? AND status='SUCCESS'", order);
              if (p == null) throw new IllegalStateException("Paid order missing payment");
              String id = UUID.randomUUID().toString();
              db.jdbc.update(
                  "INSERT INTO tb_refund(id,payment_id,order_id,user_id,request_id,amount,reason) VALUES(?,?,?,?,?,?,'USER')",
                  id,
                  p.get("id"),
                  order,
                  user,
                  key,
                  p.get("amount"));
              db.jdbc.update(
                  "UPDATE tb_user_voucher SET status='REFUNDING' WHERE order_id=?", order);
              // 先冻结券并提交退款意图，再调用模拟渠道；等待结果期间不能继续核销。
              db.jdbc.update("UPDATE tb_voucher_order SET status=5 WHERE id=? AND status=2", order);
              return refundView(id, user);
            });
    completeRefund(r.get("id").toString());
    return refundView(r.get("id").toString(), user);
  }

  public Map<String, Object> refundView(String id, long user) {
    Map<String, Object> r = db.one("SELECT * FROM tb_refund WHERE id=?", id);
    if (r == null || BusinessStore.number(r, "user_id") != user)
      throw new BusinessException(404, "REFUND_NOT_FOUND", "退款单不存在或无权查看");
    Map<String, Object> v = BusinessStore.view(r);
    v.put("simulated", true);
    return v;
  }

  public void completeRefund(String id) {
    tx(
        () -> {
          Map<String, Object> r = db.one("SELECT * FROM tb_refund WHERE id=?", id);
          if (r == null || !"PENDING".equals(r.get("status"))) return null;
          long order = BusinessStore.number(r, "order_id");
          Map<String, Object> d = lock(order, null);
          r = db.one("SELECT * FROM tb_refund WHERE id=? FOR UPDATE", id);
          if (!"PENDING".equals(r.get("status"))) return null;
          Map<String, Object> p =
              db.one(
                  "SELECT * FROM tb_payment WHERE id=? AND status='SUCCESS'", r.get("payment_id"));
          if (p == null || BusinessStore.number(r, "amount") != BusinessStore.number(p, "amount"))
            throw new IllegalStateException("Refund amount invariant");
          if (!"LATE_PAYMENT".equals(r.get("reason"))) {
            if (db.jdbc.update(
                    "UPDATE tb_voucher_order SET status=6,refund_time=CURRENT_TIMESTAMP WHERE id=? AND status=5",
                    order)
                != 1) throw new IllegalStateException("Refund state invariant");
            db.jdbc.update(
                "UPDATE tb_user_voucher SET status='REFUNDED' WHERE order_id=? AND status='REFUNDING'",
                order);
            release(order, d, "REFUND");
          }
          db.jdbc.update(
              "UPDATE tb_refund SET status='SUCCESS',refund_time=CURRENT_TIMESTAMP,last_error=NULL WHERE id=?",
              id);
          return null;
        });
  }

  public Map<String, Object> redeem(String code, long operator, String request, long consumption) {
    schema.require();
    String key = BusinessStore.key(request);
    return tx(
        () -> {
          Map<String, Object> v = db.one("SELECT * FROM tb_user_voucher WHERE code=?", code);
          if (v == null) throw new BusinessException(404, "VOUCHER_NOT_FOUND", "核销码不存在");
          access.shop(BusinessStore.number(v, "shop_id"));
          long id = BusinessStore.number(v, "order_id");
          Map<String, Object> d = lock(id, null);
          Map<String, Object> old =
              db.one(
                  "SELECT * FROM tb_redemption WHERE operator_id=? AND request_id=?",
                  operator,
                  key);
          if (old != null) {
            if (!v.get("id").equals(old.get("user_voucher_id")))
              throw new BusinessException(409, "KEY_CONFLICT", "核销编号已用于其他券");
            return BusinessStore.view(old);
          }
          v = db.one("SELECT * FROM tb_user_voucher WHERE order_id=? FOR UPDATE", id);
          if (!"UNUSED".equals(v.get("status")) || BusinessStore.number(d, "status") != 2)
            throw new BusinessException(409, "VOUCHER_NOT_USABLE", "优惠券已使用或不可核销");
          if (db.count(
                  "SELECT COUNT(*) FROM tb_user_voucher WHERE order_id=? AND (valid_until IS NULL OR valid_until>=CURRENT_TIMESTAMP)",
                  id)
              == 0) throw new BusinessException(409, "VOUCHER_EXPIRED", "优惠券已过期");
          if (consumption <= 0)
            throw new BusinessException(400, "INVALID_CONSUMPTION", "消费金额必须为正数（单位：分）");
          if (consumption < BusinessStore.number(d, "min_spend"))
            throw new BusinessException(409, "MIN_SPEND", "消费金额未满足用券条件");
          String redemption = UUID.randomUUID().toString();
          db.jdbc.update(
              "INSERT INTO tb_redemption(id,user_voucher_id,order_id,shop_id,operator_id,request_id,consumption_amount) VALUES(?,?,?,?,?,?,?)",
              redemption,
              v.get("id"),
              id,
              v.get("shop_id"),
              operator,
              key,
              consumption);
          db.jdbc.update(
              "UPDATE tb_user_voucher SET status='USED',use_time=CURRENT_TIMESTAMP WHERE order_id=?",
              id);
          db.jdbc.update(
              "UPDATE tb_voucher_order SET status=3,use_time=CURRENT_TIMESTAMP WHERE id=? AND status=2",
              id);
          return BusinessStore.view(db.one("SELECT * FROM tb_redemption WHERE id=?", redemption));
        });
  }

  public List<Map<String, Object>> mine(long user, int page) {
    schema.require();
    BusinessStore.page(page, 10);
    return BusinessStore.views(
        db.jdbc.queryForList(
            "SELECT v.*,d.title,d.pay_value,d.actual_value,d.rules,d.min_spend,CASE WHEN v.status='UNUSED' AND v.valid_until<CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE v.status END status FROM tb_user_voucher v JOIN tb_order_detail d ON d.order_id=v.order_id WHERE v.user_id=? ORDER BY v.create_time DESC,v.id LIMIT 10 OFFSET ?",
            user,
            (page - 1) * 10));
  }

  public Map<String, Object> voucher(String id, long user) {
    schema.require();
    Map<String, Object> v =
        db.one(
            "SELECT v.*,d.title,d.rules,d.actual_value,d.min_spend FROM tb_user_voucher v JOIN tb_order_detail d ON d.order_id=v.order_id WHERE v.id=? AND v.user_id=?",
            id,
            user);
    if (v == null) throw new BusinessException(404, "VOUCHER_NOT_FOUND", "优惠券不存在或无权查看");
    return BusinessStore.view(v);
  }

  public List<Long> expired() {
    if (!schema.ready()) return Collections.emptyList();
    return db.jdbc.queryForList(
        "SELECT o.id FROM tb_voucher_order o JOIN tb_order_detail d ON d.order_id=o.id WHERE o.status=1 AND d.expires_at<=CURRENT_TIMESTAMP ORDER BY d.expires_at,o.id LIMIT 100",
        Long.class);
  }

  @Scheduled(fixedDelay = 2000, initialDelay = 2000)
  public void recover() {
    // 通知/退款处理失败会留待下一轮；达到重试上限后进入阻塞态，由管理员修复后重投。
    // 普通 HTTP 重放不能绕过阻塞态，这样持续的数据错误不会被无限重试掩盖。
    if (!schema.ready() || !mock) return;
    for (String id :
        db.jdbc.queryForList(
            "SELECT id FROM tb_payment_notice WHERE state=0 AND attempts<8 ORDER BY create_time LIMIT 20",
            String.class)) {
      try {
        processNotice(id);
      } catch (Exception e) {
        db.jdbc.update(
            "UPDATE tb_payment_notice SET attempts=attempts+1,last_error=?,state=IF(attempts>=8,3,0) WHERE id=? AND state=0",
            e.getClass().getSimpleName(),
            id);
      }
    }
    drainRefunds();
  }

  private void drainRefunds() {
    if (!schema.ready() || !mock) return;
    for (String id :
        db.jdbc.queryForList(
            "SELECT id FROM tb_refund WHERE status='PENDING' AND attempts<8 ORDER BY create_time LIMIT 20",
            String.class)) {
      try {
        completeRefund(id);
      } catch (Exception e) {
        db.jdbc.update(
            "UPDATE tb_refund SET attempts=attempts+1,last_error=?,status=IF(attempts>=8,'BLOCKED','PENDING') WHERE id=? AND status='PENDING'",
            e.getClass().getSimpleName(),
            id);
      }
    }
  }
}
