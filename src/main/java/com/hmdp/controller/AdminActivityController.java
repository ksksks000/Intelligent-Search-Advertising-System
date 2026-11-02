package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Activity;
import com.hmdp.service.IActivityService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 管理端活动接口：由 AdminAuthInterceptor 校验 X-Admin-Token。
 */
@RestController
@RequestMapping("/admin/activity")
public class AdminActivityController {

    @Resource
    private IActivityService activityService;

    @PostMapping
    public Result save(@RequestBody Activity activity) {
        return activityService.saveActivity(activity);
    }

    @PutMapping
    public Result update(@RequestBody Activity activity) {
        return activityService.updateActivity(activity);
    }

    @DeleteMapping("/{id}")
    public Result offline(@PathVariable("id") Long id) {
        return activityService.offlineActivity(id);
    }

    @GetMapping("/{id}")
    public Result detail(@PathVariable("id") Long id) {
        return activityService.queryAdminById(id);
    }
}
