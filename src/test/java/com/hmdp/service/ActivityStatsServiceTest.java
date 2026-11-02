package com.hmdp.service;

import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityStatsView;
import com.hmdp.entity.ActivityStats;
import com.hmdp.mapper.ActivityStatsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityStatsServiceTest {

    private ActivityStatsMapper mapper;
    private ActivityStatsService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ActivityStatsMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HyperLogLogOperations<String, String> hll = mock(HyperLogLogOperations.class);
        when(redis.opsForHyperLogLog()).thenReturn(hll);
        service = new ActivityStatsService();
        ReflectionTestUtils.setField(service, "statsMapper", mapper);
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "properties", new RecommendProperties());
    }

    @Test
    @DisplayName("曝光事件累加 pv 并写 UV HLL")
    void recordImpression() {
        service.recordEvent(1L, "impression", "u1");
        verify(mapper).upsert(eq(1L), eq(LocalDate.now()), eq(1L), eq(0L), eq(0L));
    }

    @Test
    @DisplayName("近 7 天统计聚合：ctr=click/pv，cvr=order/click")
    void loadStatsAggregates() {
        ActivityStats a = new ActivityStats().setActivityId(1L).setPv(10L)
                .setClickCnt(2L).setOrderCnt(1L);
        ActivityStats b = new ActivityStats().setActivityId(1L).setPv(10L)
                .setClickCnt(3L).setOrderCnt(1L);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(a, b));

        Map<Long, ActivityStatsView> stats = service.loadStats(Arrays.asList(1L), 7);
        ActivityStatsView view = stats.get(1L);
        assertEquals(20L, view.getPv());
        assertEquals(5L, view.getClickCnt());
        assertEquals(2L, view.getOrderCnt());
        assertEquals(0.25D, view.getCtr(), 0.0001D);
        assertEquals(0.4D, view.getCvr(), 0.0001D);
    }
}
