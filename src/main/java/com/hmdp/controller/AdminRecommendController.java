package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserProfile;
import com.hmdp.geo.FollowRedisRebuildRunner;
import com.hmdp.search.embedding.EmbeddingClient;
import com.hmdp.search.index.SearchIndexService;
import com.hmdp.service.ActivityStatsService;
import com.hmdp.service.BehaviorEventService;
import com.hmdp.service.recommend.InterestVectorService;
import com.hmdp.utils.RecommendUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推荐管理端：follows 重建、统计聚合、健康检查。由 AdminAuthInterceptor 鉴权。
 */
@RestController
@RequestMapping("/admin/recommend")
public class AdminRecommendController {

    @Resource
    private FollowRedisRebuildRunner followRedisRebuildRunner;

    @Resource
    private ActivityStatsService activityStatsService;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private SearchIndexService searchIndexService;

    @Resource
    private BehaviorEventService behaviorEventService;

    @Resource
    private InterestVectorService interestVectorService;

    @PostMapping("/interest/rebuild")
    public Result rebuildInterest(@RequestParam(value = "userId", required = false) Long userId,
                                  @RequestParam(value = "deviceId", required = false) String deviceId) {
        String userKey = RecommendUtils.userKey(userId, deviceId);
        UserProfile profile = behaviorEventService.buildProfile(userId, deviceId, 7, 50);
        float[] vector = interestVectorService.buildAndCache(profile, userKey);
        if (vector == null) {
            return Result.fail("兴趣向量构建失败");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userKey", userKey);
        data.put("dim", vector.length);
        data.put("totalClicks", profile.getTotalClicks());
        data.put("categoryClicks", profile.getCategoryClickCounts());
        return Result.ok(data);
    }

    @PostMapping("/follows/rebuild")
    public Result rebuildFollows() {
        return Result.ok(followRedisRebuildRunner.rebuild());
    }

    @PostMapping("/stats/aggregate")
    public Result aggregate(@RequestParam(value = "date", required = false) String date) {
        LocalDate target = StrUtil.isBlank(date)
                ? LocalDate.now().minusDays(1) : LocalDate.parse(date);
        return Result.ok(activityStatsService.aggregate(target));
    }

    @GetMapping("/health")
    public Result health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("embeddingHealthy", embeddingClient.isHealthy());
        health.put("indexAlias", searchIndexService.getAlias());
        health.put("indexCount", searchIndexService.count());
        return Result.ok(health);
    }
}
