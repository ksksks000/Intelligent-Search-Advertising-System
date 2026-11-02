package com.hmdp.config;

import com.hmdp.interceptor.AdminAuthInterceptor;
import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RateLimitInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RateLimitInterceptor rateLimitInterceptor;
    @Resource
    private AdminAuthInterceptor adminAuthInterceptor;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login",
                        "/upload/**",
                        "/voucher/**",
                        "/user/code",
                        "/shop/**",
                        "/shop-type/**",
                        "/blog/hot",
                        "/activity/**",
                        "/category/**",
                        "/search/**",
                        "/actuator/**",
                        "/admin/**",
                        "/recommend/**",
                        "/ad/**"
                ).order(2);
        //管理端鉴权：只校验 X-Admin-Token，不要求用户登录
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**")
                .order(1);
        //秒杀入口限流：在 token 刷新之后、登录校验之前
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/voucher-order/seckill/**", "/search/activity", "/recommend/feed")
                .order(1);
        //token刷新的拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);
    }
}
