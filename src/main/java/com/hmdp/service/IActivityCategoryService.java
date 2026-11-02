package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.ActivityCategory;

import java.util.List;

public interface IActivityCategoryService extends IService<ActivityCategory> {

    List<ActivityCategory> listSorted();
}
