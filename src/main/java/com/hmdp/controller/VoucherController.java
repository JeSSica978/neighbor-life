package com.hmdp.controller;

import com.hmdp.business.CatalogService;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherService;
import java.util.Map;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/voucher")
public class VoucherController {
  @Resource private CatalogService catalog;
  @Resource private IVoucherService vouchers;

  @PostMapping("/seckill")
  public Result addSeckillVoucher(@RequestBody Map<String, Object> b) {
    return Result.ok(Long.toString(catalog.createVoucher(b, 1)));
  }

  @PostMapping
  public Result addVoucher(@RequestBody Map<String, Object> b) {
    return Result.ok(Long.toString(catalog.createVoucher(b, 0)));
  }

  @GetMapping("/list/{shopId}")
  public Result queryVoucherOfShop(@PathVariable Long shopId) {
    return vouchers.queryVoucherOfShop(shopId);
  }
}
