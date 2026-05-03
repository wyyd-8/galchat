package com.me.galchat.interceptor;

import com.me.galchat.utils.CurrentHolder;
import com.me.galchat.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 令牌校验拦截器
 */
@Component
@Slf4j
public class TokenInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        /*//1.获取请求路径
        String uri = request.getRequestURI();
        //2.如果是登录请求，直接放行
        if(uri.contains("/login")){
            log.info("登录请求");
            return true;
        }*/
        //3.获取token
        String token = request.getHeader("token");
        //4.如果token不存在返回错误信息401
        if(token == null || token.isEmpty()){
            log.info("用户未登录");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        //5.如果token存在则校验token，不通过返回错误信息401
        try {
            Claims claims = JwtUtils.parseToken(token);
            Integer id = (Integer) claims.get("id");
            log.info("登录员工id:{}", id);
            CurrentHolder.setCurrentId(id);
        } catch (JwtException e) {
            log.info("不正确的token");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        //6.通过放行
        log.info("token通过");
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        CurrentHolder.remove();
    }
}
