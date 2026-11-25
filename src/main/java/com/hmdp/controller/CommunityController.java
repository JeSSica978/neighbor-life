package com.hmdp.controller;

import com.hmdp.business.*;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import java.util.*;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
public class CommunityController {
  @Resource private SocialService social;
  @Resource private BusinessStore db;

  @PutMapping("/shop/{id}/favorite")
  public Result favorite(@PathVariable long id) {
    social.favorite(BusinessStore.user(), id, true);
    return Result.ok();
  }

  @DeleteMapping("/shop/{id}/favorite")
  public Result unfavorite(@PathVariable long id) {
    social.favorite(BusinessStore.user(), id, false);
    return Result.ok();
  }

  @GetMapping("/user/favorites")
  public Result favorites(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
    return social.favorites(BusinessStore.user(), page, size);
  }

  @PutMapping("/blog/{id}/like")
  public Result like(@PathVariable long id, @RequestBody Map<String, Object> b) {
    if (!(b.get("liked") instanceof Boolean))
      throw new BusinessException(400, "INVALID_LIKE", "须明确点赞或取消状态");
    social.like(id, BusinessStore.user(), Boolean.TRUE.equals(b.get("liked")));
    return Result.ok();
  }

  @PatchMapping("/blog/{id}")
  public Result edit(@PathVariable long id, @RequestBody Map<String, Object> b) {
    social.edit(id, BusinessStore.user(), b);
    return Result.ok();
  }

  @DeleteMapping("/blog/{id}")
  public Result delete(@PathVariable long id) {
    social.delete(id, BusinessStore.user());
    return Result.ok();
  }

  @GetMapping("/blog/{id}/comments")
  public Result comments(@PathVariable long id, @RequestParam(defaultValue = "1") int page) {
    return social.comments(id, page);
  }

  @PostMapping("/blog/{id}/comments")
  public Result comment(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return Result.ok(Long.toString(social.comment(id, BusinessStore.user(), b)));
  }

  @DeleteMapping("/blog-comments/{id}")
  public Result deleteComment(@PathVariable long id) {
    social.deleteComment(id, BusinessStore.user());
    return Result.ok();
  }

  @GetMapping("/community/users/{id}")
  public Result person(@PathVariable long id) {
    return Result.ok(
        social.person(id, UserHolder.getUser() == null ? 0 : UserHolder.getUser().getId()));
  }

  @PostMapping("/voucher-order/{id}/review")
  public Result review(@PathVariable long id, @RequestBody Map<String, Object> b) {
    social.review(id, BusinessStore.user(), b);
    return Result.ok();
  }

  @GetMapping("/shop/{id}/reviews")
  public Result reviews(@PathVariable long id, @RequestParam(defaultValue = "1") int page) {
    BusinessStore.page(page, 10);
    return Result.ok(
        BusinessStore.views(
            db.jdbc.queryForList(
                "SELECT r.*,u.nick_name name FROM tb_shop_review r JOIN tb_user u ON u.id=r.user_id WHERE r.shop_id=? ORDER BY r.create_time DESC,r.order_id DESC LIMIT 10 OFFSET ?",
                id,
                (page - 1) * 10)),
        db.count("SELECT COUNT(*) FROM tb_shop_review WHERE shop_id=?", id));
  }
}
