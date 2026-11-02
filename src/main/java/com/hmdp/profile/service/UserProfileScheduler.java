package com.hmdp.profile.service;

import com.hmdp.config.ProfileProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 每 6 小时扫描近 7 天有行为的用户，异步触发画像生成。
 */
@Slf4j
@Component
public class UserProfileScheduler {

    @Resource
    private ProfileProperties properties;
    @Resource
    private UserBehaviorAggregator aggregator;
    @Resource
    private UserProfileService profileService;
    @Resource(name = "profileExecutor")
    private ThreadPoolTaskExecutor profileExecutor;

    @Scheduled(fixedDelayString = "${hmdp.profile.aggregate-interval-ms:21600000}",
            initialDelayString = "${hmdp.profile.aggregate-initial-delay-ms:300000}")
    public void refreshAll() {
        if (!properties.isEnabled()) {
            return;
        }
        List<Long> userIds = aggregator.activeUserIds();
        int submitted = 0;
        for (Long userId : userIds) {
            if (rebuildAsync(userId)) {
                submitted++;
            }
        }
        for (String deviceId : aggregator.activeDeviceIds()) {
            if (rebuildDeviceAsync(deviceId)) {
                submitted++;
            }
        }
        log.info("画像定时任务提交完成 users={}, submitted={}", userIds.size(), submitted);
    }

    public boolean rebuildAsync(Long userId) {
        if (userId == null) {
            return false;
        }
        return submit(() -> profileService.rebuild(userId), "userId=" + userId);
    }

    public boolean rebuildDeviceAsync(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return false;
        }
        return submit(() -> profileService.rebuildDevice(deviceId), "deviceId=" + deviceId);
    }

    private boolean submit(Runnable task, String label) {
        try {
            profileExecutor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("异步画像生成失败 {}", label, e);
                }
            });
            return true;
        } catch (Exception e) {
            log.warn("画像线程池拒绝 {} error={}", label, e.getMessage());
            return false;
        }
    }
}
