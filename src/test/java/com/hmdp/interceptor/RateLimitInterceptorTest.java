package com.hmdp.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.RateLimitResult;
import com.hmdp.metrics.SeckillMetrics;
import com.hmdp.service.RateLimiter;
import com.hmdp.utils.UserHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 纯单元测试：限流拦截器的放行/拒绝响应。
 */
class RateLimitInterceptorTest {

    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final RateLimitInterceptor interceptor =
            new RateLimitInterceptor(rateLimiter, new ObjectMapper(),
                    new SeckillMetrics(new SimpleMeterRegistry()));

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
    }

    @Test
    @DisplayName("放行：限流通过返回 true")
    void allowed() throws Exception {
        when(rateLimiter.tryAcquire(any(), any(), any())).thenReturn(RateLimitResult.allow(10));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher-order/seckill/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, null));
    }

    @Test
    @DisplayName("拒绝：返回 429、Retry-After 与提示文案")
    void rejected() throws Exception {
        when(rateLimiter.tryAcquire(any(), any(), any()))
                .thenReturn(new RateLimitResult(false, 0, 1));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher-order/seckill/1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(429, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("请求过于频繁"));
    }
}
