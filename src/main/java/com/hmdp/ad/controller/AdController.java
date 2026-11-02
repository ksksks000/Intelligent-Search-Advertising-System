package com.hmdp.ad.controller;

import com.hmdp.ad.dto.AdAuctionRequest;
import com.hmdp.ad.dto.AdAuctionResponse;
import com.hmdp.ad.dto.AdEventRequest;
import com.hmdp.ad.service.AdAuctionService;
import com.hmdp.ad.service.AdEventService;
import com.hmdp.ad.service.AdRateLimiter;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserProfile;
import com.hmdp.service.BehaviorEventService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * 广告公开接口：竞价、点击、转化。登录拦截器放行 /ad/**，竞价限流单独挂载。
 */
@RestController
@RequestMapping("/ad")
public class AdController {

    @Resource
    private AdAuctionService auctionService;
    @Resource
    private AdEventService eventService;
    @Resource(name = "adRateLimiter")
    private AdRateLimiter adRateLimiter;
    @Resource
    private BehaviorEventService behaviorEventService;

    @PostMapping("/auction")
    public Result auction(@RequestBody(required = false) AdAuctionRequest body,
                          @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                          @RequestHeader(value = "X-Device-Type", required = false) String deviceType,
                          @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                          HttpServletRequest request, HttpServletResponse response) {
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        String ip = clientIp(request);
        if (!adRateLimiter.tryAcquire(userId, ip)) {
            response.setStatus(429);
            return Result.fail("请求过于频繁，请稍后重试");
        }
        AdAuctionRequest req = body == null ? new AdAuctionRequest() : body;
        String city = req.getCity() == null ? "杭州" : req.getCity();
        int slotCount = req.getSlotCount() == null ? 1 : Math.max(1, Math.min(10, req.getSlotCount()));
        UserProfile profile = null;
        if (behaviorEventService.hasBehavior(userId, deviceId, 7, 1)) {
            profile = behaviorEventService.buildProfile(userId, deviceId, 7, 50);
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < slotCount; i++) {
            labels.add((req.getPlacement() == null ? "ad" : req.getPlacement()) + "-" + (i + 1));
        }
        AdAuctionResponse result = auctionService.auction(requestId, userId, deviceId, ip,
                city, deviceType, slotCount, profile, labels);
        return Result.ok(result);
    }

    @PostMapping("/click")
    public Result click(@RequestBody AdEventRequest event,
                        @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                        HttpServletRequest request) {
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        eventService.recordClick(event, userId, event.getDeviceId() == null
                ? deviceId : event.getDeviceId(), clientIp(request));
        return Result.ok();
    }

    @PostMapping("/conversion")
    public Result conversion(@RequestBody AdEventRequest event,
                             @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        boolean ok = eventService.recordConversion(event, userId,
                event.getDeviceId() == null ? deviceId : event.getDeviceId());
        return ok ? Result.ok(true) : Result.fail("转化关联失败或已超时");
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
}
