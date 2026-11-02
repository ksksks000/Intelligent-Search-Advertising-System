package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IActivityCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/category")
public class CategoryController {

    @Resource
    private IActivityCategoryService activityCategoryService;

    @GetMapping("/list")
    public Result list() {
        return Result.ok(activityCategoryService.listSorted());
    }
}
