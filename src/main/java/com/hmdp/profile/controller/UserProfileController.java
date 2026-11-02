package com.hmdp.profile.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.UserProfileRecord;
import com.hmdp.profile.dto.ProfileTagsRequest;
import com.hmdp.profile.service.UserProfileService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 用户端画像标签查看/编辑，走登录拦截器。
 */
@RestController
@RequestMapping("/user/profile")
public class UserProfileController {

    @Resource
    private UserProfileService profileService;

    @GetMapping
    public Result get() {
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        if (userId == null) {
            return Result.fail("未登录");
        }
        UserProfileRecord record = profileService.get(userId);
        return record == null ? Result.ok() : Result.ok(record);
    }

    @PutMapping("/tags")
    public Result updateTags(@RequestBody ProfileTagsRequest request) {
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        if (userId == null) {
            return Result.fail("未登录");
        }
        if (request == null || request.getTags() == null) {
            return Result.fail("tags不能为空");
        }
        return Result.ok(profileService.updateTags(userId, request.getTags()));
    }
}
