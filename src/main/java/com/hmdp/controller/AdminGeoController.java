package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IActivityService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/admin/geo")
public class AdminGeoController {

    @Resource
    private IActivityService activityService;

    @PostMapping("/rebuild")
    public Result rebuild() {
        return Result.ok(activityService.rebuildGeo());
    }
}
