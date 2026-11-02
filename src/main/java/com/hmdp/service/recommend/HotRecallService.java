package com.hmdp.service.recommend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.dto.ActivityStatsView;
import com.hmdp.dto.RecommendContext;
import com.hmdp.entity.Activity;
import com.hmdp.service.ActivityStatsService;
import com.hmdp.service.IActivityService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 热门召回：在线 + 未过期 + qualityScore + 近期 ctr。
 */
@Service
public class HotRecallService implements RecallService {

    @Resource
    private IActivityService activityService;

    @Resource
    private ActivityStatsService activityStatsService;

    @Resource
    private RecommendProperties properties;

    @Override
    public String source() {
        return "HOT";
    }

    @Override
    public List<ActivityCandidate> recall(RecommendContext context) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<Activity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Activity::getStatus, Activity.STATUS_ONLINE)
                .ge(Activity::getEndTime, now)
                .le(Activity::getBeginTime, now.plusDays(7))
                .eq(context.getCity() != null && !context.getCity().trim().isEmpty(),
                        Activity::getCity, context.getCity())
                .orderByDesc(Activity::getQualityScore)
                .last("LIMIT " + Math.max(1, properties.getHotLimit()));
        List<Activity> activities = activityService.list(wrapper);
        if (activities == null || activities.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = new ArrayList<>(activities.size());
        for (Activity activity : activities) {
            ids.add(activity.getId());
        }
        Map<Long, ActivityStatsView> stats = activityStatsService.loadStats(ids, 7);
        List<ActivityCandidate> candidates = new ArrayList<>(activities.size());
        for (Activity activity : activities) {
            double quality = quality(activity);
            ActivityStatsView view = stats.get(activity.getId());
            double score;
            if (view != null && view.getPv() > 0) {
                double ctrNorm = Math.min(1D, view.getCtr() / 0.2D);
                score = 0.7D * quality + 0.3D * ctrNorm;
            } else {
                score = quality;
            }
            candidates.add(ActivityCandidate.of(activity.getId(), source(), score));
        }
        return candidates;
    }

    private double quality(Activity activity) {
        int value = activity.getQualityScore() == null ? 0 : activity.getQualityScore();
        return Math.max(0D, Math.min(1D, value / 100D));
    }
}
