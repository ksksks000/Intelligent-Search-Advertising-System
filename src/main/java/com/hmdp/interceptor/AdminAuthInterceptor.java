package com.hmdp.interceptor;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 管理端简单鉴权：请求头 X-Admin-Token 与 hmdp.admin.token 一致才放行。
 * 生产环境应替换为独立的后台账号体系。
 */
@Slf4j
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "X-Admin-Token";

    @Value("${hmdp.admin.token:}")
    private String adminToken;

    private final ObjectMapper objectMapper;

    public AdminAuthInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String actual = request.getHeader(HEADER);
        if (StrUtil.isNotBlank(adminToken) && adminToken.equals(actual)) {
            return true;
        }
        log.warn("管理端鉴权失败，uri={}, ip={}", request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail("无管理权限")));
        return false;
    }
}
