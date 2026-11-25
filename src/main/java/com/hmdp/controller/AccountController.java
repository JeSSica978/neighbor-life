package com.hmdp.controller;

import com.hmdp.business.*;
import com.hmdp.dto.Result;
import java.util.*;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {
  @Resource private AccountService accounts;
  @Resource private SigninService signs;
  @Resource private AccessService access;

  @PatchMapping("/user/me")
  public Result profile(@RequestBody Map<String, Object> b) {
    long user = BusinessStore.user();
    accounts.update(user, b);
    return Result.ok(accounts.profile(user));
  }

  @GetMapping("/user/sign/month")
  public Result month(@RequestParam String month) {
    return Result.ok(signs.month(BusinessStore.user(), month));
  }

  @GetMapping("/user/capabilities")
  public Result capabilities() {
    return Result.ok(access.capabilities());
  }
}
