package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Activity;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ActivityMapper;
import com.hmdp.service.IActivityService;
import com.hmdp.service.IShopService;
import com.hmdp.search.index.SearchIndexEvent;
import com.hmdp.search.index.SearchIndexEventPublisher;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ActivityServiceImpl extends ServiceImpl<ActivityMapper, Activity> implements IActivityService {

    private static final int GEO_BATCH_SIZE = 500;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;

    @Resource
    private SearchIndexEventPublisher searchIndexEventPublisher;

    @Override
    public Result queryById(Long id) {
        if (id == null) {
            return Result.fail("活动id不能为空");
        }
        Activity activity = cacheClient.queryWithPassThrough(
                RedisConstants.ACTIVITY_DETAIL_KEY, id, Activity.class, this::getById,
                RedisConstants.ACTIVITY_DETAIL_TTL, TimeUnit.MINUTES);
        if (activity == null || !Integer.valueOf(Activity.STATUS_ONLINE).equals(activity.getStatus())) {
            return Result.fail("活动不存在或已下线");
        }
        return Result.ok(activity);
    }

    @Override
    public Result queryAdminById(Long id) {
        if (id == null) {
            return Result.fail("活动id不能为空");
        }
        Activity activity = getById(id);
        if (activity == null) {
            return Result.fail("活动不存在");
        }
        return Result.ok(activity);
    }

    @Override
    public Result queryByShop(Long shopId) {
        if (shopId == null) {
            return Result.fail("商家id不能为空");
        }
        List<Activity> list = lambdaQuery()
                .eq(Activity::getShopId, shopId)
                .eq(Activity::getStatus, Activity.STATUS_ONLINE)
                .orderByDesc(Activity::getQualityScore)
                .orderByAsc(Activity::getBeginTime)
                .list();
        return Result.ok(list);
    }

    @Override
    public Result queryList(Integer current, Integer size, Long categoryId, Integer type, String city) {
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 10 : Math.min(size, 50);
        LambdaQueryWrapper<Activity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Activity::getStatus, Activity.STATUS_ONLINE)
                .eq(categoryId != null, Activity::getCategoryId, categoryId)
                .eq(type != null, Activity::getActivityType, type)
                .eq(StrUtil.isNotBlank(city), Activity::getCity, city)
                .orderByDesc(Activity::getQualityScore)
                .orderByAsc(Activity::getBeginTime);
        Page<Activity> page = page(new Page<>(pageNo, pageSize), wrapper);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    public Result saveActivity(Activity activity) {
        if (activity == null) {
            return Result.fail("活动不能为空");
        }
        activity.setId(null);
        if (activity.getActivityType() == null) {
            activity.setActivityType(Activity.TYPE_VOUCHER);
        }
        if (activity.getStatus() == null) {
            activity.setStatus(Activity.STATUS_DRAFT);
        }
        if (activity.getQualityScore() == null) {
            activity.setQualityScore(0);
        }
        if (activity.getSourceType() == null) {
            activity.setSourceType(0);
        }
        String error = validate(activity, null);
        if (error != null) {
            return Result.fail(error);
        }
        if (!save(activity)) {
            return Result.fail("活动保存失败");
        }
        publishIndexEvent(activity);
        return Result.ok(activity.getId());
    }

    @Override
    public Result updateActivity(Activity activity) {
        if (activity == null || activity.getId() == null) {
            return Result.fail("活动id不能为空");
        }
        Activity existing = getById(activity.getId());
        if (existing == null) {
            return Result.fail("活动不存在");
        }
        // PUT 采用全量语义：未传的排序类字段回落到库中值，避免被误置空
        if (activity.getTitle() == null) {
            activity.setTitle(existing.getTitle());
        }
        if (activity.getActivityType() == null) {
            activity.setActivityType(existing.getActivityType());
        }
        if (activity.getStatus() == null) {
            activity.setStatus(existing.getStatus());
        }
        String error = validate(activity, existing);
        if (error != null) {
            return Result.fail(error);
        }
        if (!updateById(activity)) {
            return Result.fail("活动更新失败");
        }
        evictActivityCache(activity.getId());
        publishIndexEvent(activity);
        return Result.ok();
    }

    @Override
    public Result offlineActivity(Long id) {
        if (id == null || getById(id) == null) {
            return Result.fail("活动不存在");
        }
        boolean updated = lambdaUpdate()
                .eq(Activity::getId, id)
                .set(Activity::getStatus, Activity.STATUS_OFFLINE)
                .update();
        if (!updated) {
            return Result.fail("活动下线失败");
        }
        evictActivityCache(id);
        searchIndexEventPublisher.publish(id, SearchIndexEvent.DELETE);
        return Result.ok();
    }

    @Override
    public boolean isActivityOnline(Long id) {
        if (id == null) {
            return false;
        }
        String key = RedisConstants.ACTIVITY_STATUS_KEY + id;
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return "2".equals(cached);
            }
        } catch (Exception e) {
            log.warn("读取活动状态缓存失败，回源 DB，activityId={}", id, e);
        }
        Activity activity = getById(id);
        boolean online = activity != null
                && Integer.valueOf(Activity.STATUS_ONLINE).equals(activity.getStatus());
        try {
            stringRedisTemplate.opsForValue().set(key, online ? "2" : "0",
                    RedisConstants.ACTIVITY_STATUS_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入活动状态缓存失败，activityId={}", id, e);
        }
        return online;
    }

    private void publishIndexEvent(Activity activity) {
        boolean online = Integer.valueOf(Activity.STATUS_ONLINE).equals(activity.getStatus());
        searchIndexEventPublisher.publish(activity.getId(),
                online ? SearchIndexEvent.UPSERT : SearchIndexEvent.DELETE);
    }

    private void evictActivityCache(Long id) {
        stringRedisTemplate.delete(RedisConstants.ACTIVITY_DETAIL_KEY + id);
        stringRedisTemplate.delete(RedisConstants.ACTIVITY_STATUS_KEY + id);
    }

    @Override
    public Map<String, Object> rebuildGeo() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Activity> activities = lambdaQuery()
                .eq(Activity::getStatus, Activity.STATUS_ONLINE)
                .isNotNull(Activity::getX)
                .isNotNull(Activity::getY)
                .list();
        Map<String, List<Activity>> byCity = activities.stream()
                .filter(activity -> StrUtil.isNotBlank(activity.getCity()))
                .collect(Collectors.groupingBy(activity -> activity.getCity().trim()));
        int activityCount = 0;
        if (!byCity.isEmpty()) {
            stringRedisTemplate.delete(RedisConstants.ACTIVITY_GEO_KEY_LEGACY);
            for (Map.Entry<String, List<Activity>> entry : byCity.entrySet()) {
                String key = RedisConstants.ACTIVITY_GEO_KEY_PREFIX + entry.getKey();
                stringRedisTemplate.delete(key);
                activityCount += addActivityGeo(key, entry.getValue());
            }
        }

        List<Shop> shops = shopService.list().stream()
                .filter(shop -> shop.getX() != null && shop.getY() != null)
                .collect(Collectors.toList());
        Map<Long, List<Shop>> byType = shops.stream()
                .filter(shop -> shop.getTypeId() != null)
                .collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : byType.entrySet()) {
            String key = RedisConstants.SHOP_GEO_KEY + entry.getKey();
            stringRedisTemplate.delete(key);
            List<RedisGeoCommands.GeoLocation<String>> locations = entry.getValue().stream()
                    .map(shop -> new RedisGeoCommands.GeoLocation<>(
                            shop.getId().toString(), new Point(shop.getX(), shop.getY())))
                    .collect(Collectors.toList());
            for (int i = 0; i < locations.size(); i += GEO_BATCH_SIZE) {
                stringRedisTemplate.opsForGeo().add(key,
                        locations.subList(i, Math.min(i + GEO_BATCH_SIZE, locations.size())));
            }
        }

        result.put("activityCount", activityCount);
        result.put("cityCount", byCity.size());
        result.put("shopCount", shops.size());
        result.put("shopTypeCount", byType.size());
        log.info("GEO 重建完成，活动 {} 条，商家 {} 条，类目 {} 个",
                activityCount, shops.size(), byType.size());
        return result;
    }

    private int addActivityGeo(String key, List<Activity> activities) {
        List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(activities.size());
        for (Activity activity : activities) {
            locations.add(new RedisGeoCommands.GeoLocation<>(
                    activity.getId().toString(), new Point(activity.getX(), activity.getY())));
        }
        for (int i = 0; i < locations.size(); i += GEO_BATCH_SIZE) {
            stringRedisTemplate.opsForGeo().add(key,
                    locations.subList(i, Math.min(i + GEO_BATCH_SIZE, locations.size())));
        }
        return locations.size();
    }

    private String validate(Activity activity, Activity existing) {
        if (StrUtil.isBlank(activity.getTitle())) {
            return "活动标题不能为空";
        }
        Integer type = activity.getActivityType();
        if (type == null || type < Activity.TYPE_VOUCHER || type > Activity.TYPE_SECKILL) {
            return "活动类型不合法";
        }
        Integer status = activity.getStatus();
        if (status == null || status < Activity.STATUS_DRAFT || status > Activity.STATUS_EXPIRED) {
            return "活动状态不合法";
        }
        if (activity.getShopId() != null && shopService.getById(activity.getShopId()) == null) {
            return "商家不存在";
        }
        Double x = activity.getX() != null ? activity.getX()
                : existing == null ? null : existing.getX();
        Double y = activity.getY() != null ? activity.getY()
                : existing == null ? null : existing.getY();
        if ((x == null) != (y == null)) {
            return "经纬度必须同时填写";
        }
        LocalDateTime begin = activity.getBeginTime() != null ? activity.getBeginTime()
                : existing == null ? null : existing.getBeginTime();
        LocalDateTime end = activity.getEndTime() != null ? activity.getEndTime()
                : existing == null ? null : existing.getEndTime();
        if (begin != null && end != null && !begin.isBefore(end)) {
            return "开始时间必须早于结束时间";
        }
        if (activity.getOriginalPrice() != null && activity.getOriginalPrice() < 0) {
            return "原价不能为负";
        }
        if (activity.getPayValue() != null && activity.getPayValue() < 0) {
            return "支付金额不能为负";
        }
        if (activity.getStock() != null && activity.getStock() < 0) {
            return "库存不能为负";
        }
        return null;
    }
}
