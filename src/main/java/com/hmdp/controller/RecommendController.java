package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.RecEventDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.RecEventPublisher;
import com.hmdp.service.recommend.RecommendFeedService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * 推荐 Feed 公开接口 + 行为埋点。
 */
@RestController
@RequestMapping("/recommend")
public class RecommendController {

    @Resource
    private RecommendFeedService recommendFeedService;

    @Resource
    private RecEventPublisher recEventPublisher;

    @GetMapping("/feed")
    public Result feed(@RequestParam(value = "city", defaultValue = "杭州") String city,
                       @RequestParam(value = "cursor", defaultValue = "0") Integer cursor,
                       @RequestParam(value = "size", defaultValue = "10") Integer size,
                       @RequestParam(value = "ad", defaultValue = "true") Boolean ad,
                       @RequestParam(value = "x", required = false) Double x,
                       @RequestParam(value = "y", required = false) Double y,
                       @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                       @RequestHeader(value = "X-Device-Type", required = false) String deviceType,
                       HttpServletRequest request) {
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        return Result.ok(recommendFeedService.feed(userId, deviceId, city, x, y,
                cursor == null ? 0 : cursor, size == null ? 10 : size,
                ad == null || ad, clientIp(request), deviceType));
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

    @PostMapping("/event")
    public Result event(@RequestBody RecEventDTO event,
                        @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId) {
        if (event == null || event.getActivityId() == null
                || StrUtil.isBlank(event.getEventType())) {
            return Result.fail("activityId/eventType不能为空");
        }
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        String deviceId = StrUtil.isNotBlank(event.getDeviceId())
                ? event.getDeviceId() : headerDeviceId;
        if (userId == null && StrUtil.isBlank(deviceId)) {
            return Result.fail("deviceId不能为空");
        }
        event.setDeviceId(deviceId);
        if (StrUtil.isBlank(event.getTraceId())) {
            event.setTraceId(org.slf4j.MDC.get("traceId"));
        }
        recEventPublisher.publish(event, userId, deviceId);
        return Result.ok();
    }
}
