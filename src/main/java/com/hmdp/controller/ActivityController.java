package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/activity")
public class ActivityController {

    @Resource
    private IActivityService activityService;

    @GetMapping("/{id}")
    public Result queryById(@PathVariable("id") Long id) {
        return activityService.queryById(id);
    }

    @GetMapping("/of/shop/{shopId}")
    public Result queryByShop(@PathVariable("shopId") Long shopId) {
        return activityService.queryByShop(shopId);
    }

    @GetMapping("/list")
    public Result queryList(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") Integer size,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "type", required = false) Integer type,
            @RequestParam(value = "city", required = false) String city) {
        return activityService.queryList(current, size, categoryId, type, city);
    }
}
