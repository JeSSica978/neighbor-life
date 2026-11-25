package com.hmdp.controller;

import com.hmdp.business.*;
import com.hmdp.dto.Result;
import com.hmdp.mapper.ReliableEventMapper;
import java.util.*;
import javax.annotation.Resource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class MerchantController {
  @Resource private CatalogService catalog;
  @Resource private AccessService access;
  @Resource private BusinessStore db;
  @Resource private ReliableEventMapper events;
  @Resource private AuditService audit;

  @GetMapping("/admin/operations")
  public Result operations() {
    access.requireAdmin();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(
        "events",
        BusinessStore.views(
            db.jdbc.queryForList(
                "SELECT * FROM tb_reliable_event WHERE status=3 ORDER BY create_time DESC LIMIT 50")));
    out.put(
        "notices",
        BusinessStore.views(
            db.jdbc.queryForList(
                "SELECT * FROM tb_payment_notice WHERE state=3 ORDER BY create_time DESC LIMIT 50")));
    out.put(
        "refunds",
        BusinessStore.views(
            db.jdbc.queryForList(
                "SELECT * FROM tb_refund WHERE status='BLOCKED' ORDER BY create_time DESC LIMIT 50")));
    out.put(
        "audit",
        BusinessStore.views(
            db.jdbc.queryForList(
                "SELECT * FROM tb_operation_audit ORDER BY create_time DESC LIMIT 50")));
    return Result.ok(out);
  }

  @DeleteMapping("/admin/shop-members")
  @Transactional
  public Result removeMember(@RequestParam long userId, @RequestParam long shopId) {
    access.requireAdmin();
    db.jdbc.update("DELETE FROM tb_shop_member WHERE shop_id=? AND user_id=?", shopId, userId);
    audit.record("REVOKE_SHOP_MEMBER", shopId + ":" + userId);
    return Result.ok();
  }

  @GetMapping("/merchant/shops")
  public Result shops() {
    return Result.ok(catalog.shops(BusinessStore.user()));
  }

  @GetMapping("/merchant/vouchers")
  public Result vouchers(@RequestParam long shopId) {
    return Result.ok(catalog.vouchers(shopId));
  }

  @PatchMapping("/merchant/vouchers/{id}")
  public Result edit(@PathVariable long id, @RequestBody Map<String, Object> b) {
    catalog.updateVoucher(id, b);
    return Result.ok();
  }

  @PostMapping("/merchant/vouchers/{id}/status")
  public Result status(@PathVariable long id, @RequestBody Map<String, Object> b) {
    catalog.voucherStatus(id, (int) BusinessStore.number(b, "status"));
    return Result.ok();
  }

  @PostMapping("/merchant/shops/{id}/status")
  public Result shopStatus(@PathVariable long id, @RequestBody Map<String, Object> b) {
    catalog.shopStatus(id, (int) BusinessStore.number(b, "status"));
    return Result.ok();
  }

  @GetMapping("/merchant/shops/{id}/stats")
  public Result stats(@PathVariable long id) {
    return Result.ok(catalog.stats(id));
  }

  @GetMapping("/merchant/shops/{id}/redemptions")
  public Result redemptions(@PathVariable long id, @RequestParam(defaultValue = "1") int page) {
    access.shop(id);
    BusinessStore.page(page, 10);
    return Result.ok(
        BusinessStore.views(
            db.jdbc.queryForList(
                "SELECT * FROM tb_redemption WHERE shop_id=? ORDER BY create_time DESC,id LIMIT 10 OFFSET ?",
                id,
                (page - 1) * 10)),
        db.count("SELECT COUNT(*) FROM tb_redemption WHERE shop_id=?", id));
  }

  @GetMapping("/merchant/shops/{id}/events")
  public Result events(@PathVariable long id) {
    access.shop(id);
    return Result.ok(
        BusinessStore.views(
            db.jdbc.queryForList(
                "SELECT e.* FROM tb_reliable_event e LEFT JOIN tb_order_detail d ON d.order_id=e.resource_id WHERE (e.event_type IN ('SHOP_CACHE_DELETE','CATALOG_SYNC') AND e.resource_id=?) OR (e.event_type='SECKILL_STOCK_RESTORE' AND d.shop_id=?) ORDER BY e.create_time DESC LIMIT 50",
                id,
                id)));
  }

  @PostMapping("/admin/shop-members")
  @Transactional
  public Result member(@RequestBody Map<String, Object> b) {
    access.requireAdmin();
    long user = BusinessStore.positive(b, "userId"), shop = BusinessStore.positive(b, "shopId");
    if (db.count("SELECT COUNT(*) FROM tb_user WHERE id=?", user) == 0
        || db.count("SELECT COUNT(*) FROM tb_shop WHERE id=?", shop) == 0)
      throw new BusinessException(404, "MEMBER_NOT_FOUND", "用户或店铺不存在");
    db.jdbc.update(
        "INSERT INTO tb_shop_member(shop_id,user_id) VALUES(?,?) ON DUPLICATE KEY UPDATE role='OWNER'",
        shop,
        user);
    audit.record("GRANT_SHOP_MEMBER", shop + ":" + user);
    return Result.ok();
  }

  @PostMapping("/admin/events/{id}/retry")
  @Transactional
  public Result retry(@PathVariable String id) {
    access.requireAdmin();
    if (events.requeue(id) != 1) throw new BusinessException(409, "NOT_BLOCKED", "只有阻塞事件可以重投");
    audit.record("RETRY_EVENT", id);
    return Result.ok();
  }

  @PostMapping("/admin/payment-notices/{id}/retry")
  @Transactional
  public Result retryNotice(@PathVariable String id) {
    access.requireAdmin();
    if (db.jdbc.update(
            "UPDATE tb_payment_notice SET state=0,attempts=0,last_error=NULL WHERE id=? AND state=3",
            id)
        != 1) throw new BusinessException(409, "NOT_BLOCKED", "通知没有处于阻塞状态");
    audit.record("RETRY_NOTICE", id);
    return Result.ok();
  }

  @PostMapping("/admin/refunds/{id}/retry")
  @Transactional
  public Result retryRefund(@PathVariable String id) {
    access.requireAdmin();
    if (db.jdbc.update(
            "UPDATE tb_refund SET status='PENDING',attempts=0,last_error=NULL WHERE id=? AND status='BLOCKED'",
            id)
        != 1) throw new BusinessException(409, "NOT_BLOCKED", "退款没有处于阻塞状态");
    audit.record("RETRY_REFUND", id);
    return Result.ok();
  }
}
