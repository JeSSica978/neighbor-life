package com.hmdp.utils;

import javax.servlet.http.*;
import org.springframework.web.servlet.HandlerInterceptor;

public class LoginInterceptor implements HandlerInterceptor {
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String path = request.getRequestURI();
    boolean publicRead =
        "GET".equals(request.getMethod())
            && (path.matches("/shop/(of/name|of/type|[0-9]+)")
                || path.matches("/voucher/list/[0-9]+")
                || path.equals("/shop-type/list")
                || path.matches("/blog/(hot|[0-9]+|likes/[0-9]+|of/user)")
                || path.matches("/blog/[0-9]+/comments")
                || path.matches("/shop/[0-9]+/reviews")
                || path.matches("/user/[0-9]+")
                || path.matches("/community/users/[0-9]+"));
    if (publicRead || UserHolder.getUser() != null) return true;
    response.setStatus(401);
    response.setContentType("application/json;charset=UTF-8");
    response
        .getWriter()
        .write("{\"success\":false,\"code\":\"LOGIN_REQUIRED\",\"errorMsg\":\"请先登录\"}");
    return false;
  }
}
