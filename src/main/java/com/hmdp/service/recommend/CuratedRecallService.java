package com.hmdp.service.recommend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.dto.RecommendContext;
import com.hmdp.entity.Activity;
import com.hmdp.service.IActivityService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 运营精选/冷启动兜底：平台级活动 + 高质量活动。
 */
@Service
public class CuratedRecallService implements RecallService {

    @Resource
    private IActivityService activityService;

    @Resource
    private RecommendProperties properties;

    @Override
    public String source() {
        return "CURATED";
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
                .and(q -> q.isNull(Activity::getShopId).or().ge(Activity::getQualityScore, 90))
                .orderByDesc(Activity::getQualityScore)
                .last("LIMIT " + Math.max(1, properties.getCuratedLimit()));
        List<Activity> activities = activityService.list(wrapper);
        if (activities == null || activities.isEmpty()) {
            return new ArrayList<>();
        }
        List<ActivityCandidate> candidates = new ArrayList<>(activities.size());
        for (Activity activity : activities) {
            double quality = activity.getQualityScore() == null ? 0D
                    : Math.min(1D, activity.getQualityScore() / 100D);
            candidates.add(ActivityCandidate.of(activity.getId(), source(), quality));
        }
        return candidates;
    }
}
