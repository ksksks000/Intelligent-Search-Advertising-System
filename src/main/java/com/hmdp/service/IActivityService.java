package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Activity;

import java.util.Map;

public interface IActivityService extends IService<Activity> {

    Result queryById(Long id);

    Result queryAdminById(Long id);

    Result queryByShop(Long shopId);

    Result queryList(Integer current, Integer size, Long categoryId, Integer type, String city);

    Result saveActivity(Activity activity);

    Result updateActivity(Activity activity);

    Result offlineActivity(Long id);

    /**
     * 活动是否在线，供秒杀入口做活动-券联动校验（带 Redis 短缓存）。
     */
    boolean isActivityOnline(Long id);

    /**
     * 重建 shop:geo:{typeId} 与 act:geo:{city}，返回统计信息。
     */
    Map<String, Object> rebuildGeo();
}
