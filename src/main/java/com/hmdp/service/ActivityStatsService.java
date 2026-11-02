package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityStatsView;
import com.hmdp.entity.ActivityStats;
import com.hmdp.mapper.ActivityStatsMapper;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动统计：事件累加、精排读取、UV 日聚合。
 */
@Slf4j
@Service
public class ActivityStatsService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    @Resource
    private ActivityStatsMapper statsMapper;

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private RecommendProperties properties;

    /**
     * 曝光/点击/下单原子累加；曝光同时写入 UV HyperLogLog。
     */
    public void recordEvent(Long activityId, String eventType, String userKey) {
        if (activityId == null || eventType == null) {
            return;
        }
        long pv = 0;
        long click = 0;
        long order = 0;
        switch (eventType) {
            case "impression":
                pv = 1;
                break;
            case "click":
                click = 1;
                break;
            case "order":
                order = 1;
                break;
            default:
                return;
        }
        LocalDate date = LocalDate.now();
        statsMapper.upsert(activityId, date, pv, click, order);
        if (pv > 0 && userKey != null) {
            String key = uvKey(activityId, date);
            try {
                redis.opsForHyperLogLog().add(key, userKey);
                redis.expire(key, RedisConstants.REC_BEHAVIOR_TTL_SECONDS,
                        java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("写入 UV HLL 失败，activityId={}, error={}", activityId, e.getMessage());
            }
        }
    }

    /**
     * 近 N 天统计聚合；无数据时返回空 Map，由精排使用中性先验。
     */
    public Map<Long, ActivityStatsView> loadStats(Collection<Long> activityIds, int days) {
        if (activityIds == null || activityIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ActivityStats> rows;
        try {
            rows = statsMapper.selectList(new QueryWrapper<ActivityStats>()
                    .in("activity_id", activityIds)
                    .ge("stat_date", LocalDate.now().minusDays(Math.max(1, days))));
        } catch (Exception e) {
            log.warn("读取活动统计失败，使用中性先验: {}", e.getMessage());
            return Collections.emptyMap();
        }
        Map<Long, long[]> sums = new HashMap<>();
        for (ActivityStats row : rows) {
            long[] agg = sums.computeIfAbsent(row.getActivityId(), k -> new long[3]);
            agg[0] += row.getPv() == null ? 0 : row.getPv();
            agg[1] += row.getClickCnt() == null ? 0 : row.getClickCnt();
            agg[2] += row.getOrderCnt() == null ? 0 : row.getOrderCnt();
        }
        Map<Long, ActivityStatsView> result = new HashMap<>();
        for (Map.Entry<Long, long[]> entry : sums.entrySet()) {
            long pv = entry.getValue()[0];
            long click = entry.getValue()[1];
            long order = entry.getValue()[2];
            double ctr = pv > 0 ? (double) click / pv : 0D;
            double cvr = click > 0 ? (double) order / click : 0D;
            result.put(entry.getKey(), new ActivityStatsView(pv, click, order, ctr, cvr));
        }
        return result;
    }

    /**
     * 每天 00:05 聚合昨日 UV，并重算 ctr/cvr。
     */
    @Scheduled(cron = "${hmdp.recommend.stats-aggregate-cron:0 5 0 * * ?}")
    public void aggregateYesterday() {
        aggregate(LocalDate.now().minusDays(1));
    }

    public int aggregate(LocalDate date) {
        if (date == null) {
            return 0;
        }
        List<ActivityStats> rows = statsMapper.selectList(
                new QueryWrapper<ActivityStats>().eq("stat_date", date));
        int updated = 0;
        for (ActivityStats row : rows) {
            long pv = row.getPv() == null ? 0 : row.getPv();
            long click = row.getClickCnt() == null ? 0 : row.getClickCnt();
            long order = row.getOrderCnt() == null ? 0 : row.getOrderCnt();
            long uv = 0;
            try {
                Long size = redis.opsForHyperLogLog().size(uvKey(row.getActivityId(), date));
                uv = size == null ? 0 : size;
            } catch (Exception e) {
                log.warn("读取 UV HLL 失败，activityId={}, error={}", row.getActivityId(), e.getMessage());
            }
            BigDecimal ctr = pv > 0
                    ? BigDecimal.valueOf(click).divide(BigDecimal.valueOf(pv), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal cvr = click > 0
                    ? BigDecimal.valueOf(order).divide(BigDecimal.valueOf(click), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            ActivityStats update = new ActivityStats()
                    .setUv(uv)
                    .setCtr(ctr)
                    .setCvr(cvr);
            updated += statsMapper.update(update, new UpdateWrapper<ActivityStats>()
                    .eq("activity_id", row.getActivityId())
                    .eq("stat_date", date));
        }
        log.info("活动统计日聚合完成，date={}, rows={}, updated={}", date, rows.size(), updated);
        return updated;
    }

    private String uvKey(Long activityId, LocalDate date) {
        return RedisConstants.REC_UV_KEY + activityId + ":" + date.format(DAY_FORMAT);
    }
}
