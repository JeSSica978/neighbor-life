package com.hmdp.business;

import java.util.*;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class AccessService {
  @Resource private BusinessStore db;
  @Resource private BusinessSchema schema;

  public boolean admin(long user) {
    return schema.ready() && db.count("SELECT COUNT(*) FROM tb_admin WHERE user_id=?", user) > 0;
  }

  public boolean allowed(long user, long shop) {
    return schema.ready()
        && (admin(user)
            || db.count(
                    "SELECT COUNT(*) FROM tb_shop_member WHERE user_id=? AND shop_id=?", user, shop)
                > 0);
  }

  public void shop(long shop) {
    schema.require();
    if (!allowed(BusinessStore.user(), shop))
      throw new BusinessException(403, "SHOP_FORBIDDEN", "只能管理已授权的店铺");
  }

  public void requireAdmin() {
    schema.require();
    if (!admin(BusinessStore.user())) throw new BusinessException(403, "ADMIN_REQUIRED", "需要管理员权限");
  }

  public Map<String, Object> capabilities() {
    long user = BusinessStore.user();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("admin", admin(user));
    out.put(
        "merchant",
        schema.ready()
            && (admin(user)
                || db.count("SELECT COUNT(*) FROM tb_shop_member WHERE user_id=?", user) > 0));
    out.put("businessEnabled", schema.ready());
    return out;
  }
}
