package com.hmdp.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.RateLimitResult;
import com.hmdp.dto.Result;
import com.hmdp.metrics.SeckillMetrics;
import com.hmdp.service.RateLimiter;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 秒杀入口限流拦截器：在 token 刷新之后、登录校验之前执行。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final SeckillMetrics metrics;

    public RateLimitInterceptor(RateLimiter rateLimiter, ObjectMapper objectMapper, SeckillMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        String ip = clientIp(request);
        String api = apiPattern(request);

        RateLimitResult result = rateLimiter.tryAcquire(userId, ip, api);
        if (result.isAllowed()) {
            return true;
        }

        log.debug("秒杀入口限流命中，dimension={}, userId={}, ip={}, api={}",
                result.getDimension(), userId, ip, api);
        metrics.rateLimitRejected(result.getDimension());
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", "1");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail("请求过于频繁，请稍后重试")));
        return false;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty() && !"unknown".equalsIgnoreCase(forwarded)) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty() && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String apiPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? request.getRequestURI() : pattern.toString();
    }
}
