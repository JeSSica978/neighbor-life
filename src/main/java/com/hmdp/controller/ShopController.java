package com.hmdp.controller;

import com.hmdp.business.*;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.limiter.annotation.RateLimiter;
import com.hmdp.service.IShopService;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shop")
public class ShopController {
  @Resource public IShopService shopService;
  @Resource private ShopQueryService queries;
  @Resource private CatalogService catalog;

  @GetMapping("/{id}")
  public Result queryShopById(@PathVariable long id, HttpServletRequest request) {
    Result result = shopService.queryById(id);
    if (Boolean.TRUE.equals(result.getSuccess())) queries.visit(id, request);
    return result;
  }

  @PostMapping
  public Result saveShop(@RequestBody Shop shop) {
    return Result.ok(catalog.saveShop(shop).get("id"));
  }

  @PutMapping
  public Result updateShop(@RequestBody Shop shop) {
    return shopService.update(shop);
  }

  @RateLimiter(
      key = "query:shop:",
      window = 1,
      limit = 120,
      message = "查询过于频繁，请稍后",
      type = RateLimiter.LimitType.IP)
  @GetMapping("/of/type")
  public Result queryShopByType(
      @RequestParam Long typeId,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "5") int size,
      @RequestParam(defaultValue = "default") String sort,
      @RequestParam(required = false) Double x,
      @RequestParam(required = false) Double y) {
    return queries.list(null, typeId, current, size, sort, x, y);
  }

  @GetMapping("/of/name")
  public Result queryShopByName(
      @RequestParam(required = false) String name,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(defaultValue = "default") String sort,
      @RequestParam(required = false) Double x,
      @RequestParam(required = false) Double y) {
    return queries.list(name, null, current, size, sort, x, y);
  }
}
