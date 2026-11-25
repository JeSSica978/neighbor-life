package com.hmdp.business;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

import java.util.*;
import javax.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  @Resource private BusinessStore db;
  @Resource private StringRedisTemplate redis;
  @Resource private CatalogService catalog;

  public void logout(String token) {
    if (token != null && !token.isEmpty()) redis.delete(LOGIN_USER_KEY + token);
  }

  public Map<String, Object> profile(long user) {
    Map<String, Object> p =
        db.one(
            "SELECT u.id,u.nick_name,u.icon,i.city,i.introduce FROM tb_user u LEFT JOIN tb_user_info i ON i.user_id=u.id WHERE u.id=?",
            user);
    if (p == null) throw new BusinessException(404, "USER_NOT_FOUND", "用户不存在");
    return BusinessStore.view(p);
  }

  @Transactional
  public void update(long user, Map<String, Object> body) {
    Map<String, Object> old = db.one("SELECT * FROM tb_user WHERE id=? FOR UPDATE", user);
    if (body.containsKey("nickName"))
      db.jdbc.update(
          "UPDATE tb_user SET nick_name=? WHERE id=?",
          BusinessStore.required(body, "nickName", 32),
          user);
    if (body.containsKey("icon")) {
      String icon = BusinessStore.string(body, "icon");
      catalog.images(icon, user, BusinessStore.string(old, "icon"));
      db.jdbc.update("UPDATE tb_user SET icon=? WHERE id=?", icon, user);
    }
    if (body.containsKey("city") || body.containsKey("introduce")) {
      Map<String, Object> info =
          db.one("SELECT city,introduce FROM tb_user_info WHERE user_id=?", user);
      String city =
          body.containsKey("city")
              ? BusinessStore.string(body, "city")
              : BusinessStore.string(info, "city");
      String intro =
          body.containsKey("introduce")
              ? BusinessStore.string(body, "introduce")
              : BusinessStore.string(info, "introduce");
      if (city.length() > 32 || intro.length() > 128)
        throw new BusinessException(400, "INVALID_PROFILE", "城市或简介过长");
      db.jdbc.update(
          "INSERT INTO tb_user_info(user_id,city,introduce) VALUES(?,?,?) ON DUPLICATE KEY UPDATE city=VALUES(city),introduce=VALUES(introduce)",
          user,
          city,
          intro);
    }
  }
}
