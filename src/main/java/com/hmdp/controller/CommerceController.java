package com.hmdp.controller;

import com.hmdp.business.*;
import com.hmdp.dto.Result;
import java.util.*;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
public class CommerceController {
  @Resource private CommerceService commerce;
  @Resource private BusinessStore db;

  @PostMapping("/payments")
  public Result create(
      @RequestBody Map<String, Object> b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return Result.ok(
        commerce.createPayment(BusinessStore.positive(b, "orderId"), BusinessStore.user(), key));
  }

  @GetMapping("/payments/{id}")
  public Result payment(@PathVariable String id) {
    return Result.ok(commerce.payment(id, BusinessStore.user()));
  }

  @PostMapping("/mock-payments/{id}/confirm")
  public Result confirm(
      @PathVariable String id,
      @RequestBody(required = false) Map<String, Object> b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return Result.ok(
        commerce.confirm(
            id,
            BusinessStore.user(),
            key,
            b == null ? "SUCCESS" : BusinessStore.string(b, "result"),
            b != null && b.containsKey("amount") ? BusinessStore.number(b, "amount") : null));
  }

  @GetMapping("/user-vouchers/mine")
  public Result mine(@RequestParam(defaultValue = "1") int page) {
    long user = BusinessStore.user();
    return Result.ok(
        commerce.mine(user, page),
        db.count("SELECT COUNT(*) FROM tb_user_voucher WHERE user_id=?", user));
  }

  @GetMapping("/user-vouchers/{id}")
  public Result voucher(@PathVariable String id) {
    return Result.ok(commerce.voucher(id, BusinessStore.user()));
  }

  @PostMapping("/voucher-order/{id}/refunds")
  public Result refund(
      @PathVariable long id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return Result.ok(commerce.refund(id, BusinessStore.user(), key));
  }

  @GetMapping("/refunds/{id}")
  public Result refund(@PathVariable String id) {
    return Result.ok(commerce.refundView(id, BusinessStore.user()));
  }

  @PostMapping("/merchant/redemptions")
  public Result redeem(
      @RequestBody Map<String, Object> b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return Result.ok(
        commerce.redeem(
            BusinessStore.required(b, "code", 64),
            BusinessStore.user(),
            key,
            BusinessStore.number(b, "consumptionAmount")));
  }
}
