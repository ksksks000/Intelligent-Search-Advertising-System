package com.hmdp.profile.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.UserProfileRecord;
import com.hmdp.profile.service.UserProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 运营端画像查看/重建。由 AdminAuthInterceptor 校验 X-Admin-Token。
 */
@RestController
@RequestMapping("/admin/profile")
public class AdminProfileController {

    @Resource
    private UserProfileService profileService;

    @GetMapping("/{userId}")
    public Result get(@PathVariable("userId") String userId) {
        UserProfileRecord record = profileService.get(normalize(userId));
        return record == null ? Result.fail("画像不存在") : Result.ok(record);
    }

    @PostMapping("/rebuild")
    public Result rebuild(@RequestParam(value = "userId", required = false) Long userId,
                          @RequestParam(value = "deviceId", required = false) String deviceId) {
        if (userId != null) {
            return Result.ok(profileService.rebuild(userId));
        }
        if (deviceId != null && !deviceId.trim().isEmpty()) {
            return Result.ok(profileService.rebuildDevice(deviceId));
        }
        return Result.fail("userId/deviceId不能同时为空");
    }

    private String normalize(String key) {
        if (key == null || key.isEmpty()) {
            return "u0";
        }
        return (key.startsWith("u") || key.startsWith("d")) ? key : "u" + key;
    }
}
