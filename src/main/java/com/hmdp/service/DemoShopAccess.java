package com.hmdp.service;
import com.hmdp.business.AccessService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

/** 兼容旧页面路径；权限统一来自商家授权表，演示开关不再授予任意用户写权限。 */
@Component
public class DemoShopAccess {
    @Resource private AccessService access;
    public boolean allowed(Long id) { return id!=null && UserHolder.getUser()!=null && access.allowed(UserHolder.getUser().getId(),id); }
}
