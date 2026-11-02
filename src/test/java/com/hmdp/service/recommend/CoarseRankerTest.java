package com.hmdp.service.recommend;

import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.entity.Activity;
import com.hmdp.service.IActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoarseRankerTest {

    private IActivityService activityService;
    private CoarseRanker ranker;

    @BeforeEach
    void setUp() {
        activityService = mock(IActivityService.class);
        RecommendProperties properties = new RecommendProperties();
        ranker = new CoarseRanker();
        ReflectionTestUtils.setField(ranker, "activityService", activityService);
        ReflectionTestUtils.setField(ranker, "properties", properties);
    }

    @Test
    @DisplayName("来源加权 + 下线过滤 + 多路命中奖励")
    void sourceWeightAndFilter() {
        Activity a = activity(1L, Activity.STATUS_ONLINE, 1L, 1L);
        Activity b = activity(2L, Activity.STATUS_ONLINE, 2L, 2L);
        Activity offline = activity(3L, Activity.STATUS_OFFLINE, 3L, 3L);
        when(activityService.listByIds(any())).thenReturn(Arrays.asList(a, b, offline));

        ActivityCandidate follow = ActivityCandidate.of(1L, "FOLLOW", 1.0D);
        ActivityCandidate hot = ActivityCandidate.of(2L, "HOT", 1.0D);
        ActivityCandidate multi = ActivityCandidate.of(1L, "TAG", 1.0D);

        List<ActivityCandidate> result = ranker.rank(Arrays.asList(follow, hot, multi),
                new com.hmdp.dto.RecommendContext());
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getActivityId());
        assertEquals(2L, result.get(1).getActivityId());
    }

    private Activity activity(Long id, int status, Long categoryId, Long shopId) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setStatus(status);
        activity.setCategoryId(categoryId);
        activity.setShopId(shopId);
        activity.setStock(10);
        activity.setQualityScore(90);
        activity.setBeginTime(LocalDateTime.now().minusDays(1));
        activity.setEndTime(LocalDateTime.now().plusDays(1));
        return activity;
    }
}
