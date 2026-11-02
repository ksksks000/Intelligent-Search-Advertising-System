package com.hmdp.ad.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ad.dto.AdCtrSample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 阶段 5 修正 1：按时间切分，前 700 拟合、后 300 评估，避免用训练集 ECE 冒充泛化。
 */
class AdCtrTimeSplitTest {

    private AdCtrCalibrationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AdCtrCalibrationService();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        service.load();
    }

    @Test
    @DisplayName("时间切分：前700拟合、后300评估")
    void timeSplitEce() {
        List<AdCtrSample> train = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            train.add(new AdCtrSample(0.8D, i < 140 ? 1 : 0)); // 35%
        }
        for (int i = 0; i < 300; i++) {
            train.add(new AdCtrSample(0.2D, i < 30 ? 1 : 0));  // 10%
        }
        List<AdCtrSample> test = new ArrayList<>();
        for (int i = 0; i < 180; i++) {
            test.add(new AdCtrSample(0.8D, i < 45 ? 1 : 0));   // 25%，时间漂移
        }
        for (int i = 0; i < 120; i++) {
            test.add(new AdCtrSample(0.2D, i < 10 ? 1 : 0));   // 8.3%
        }
        AdCtrCalibrationService.TimeSplitResult ten = service.fitTimeSplit(train, test, 10);
        System.out.printf("CTR time-split bins=10 ECE before=%.4f after=%.4f test=%d%n",
                ten.getEceBefore(), ten.getEceAfter(), ten.getSamples());
        AdCtrCalibrationService.TimeSplitResult result = ten;
        if (ten.getEceAfter() < 0.01D) {
            result = service.fitTimeSplit(train, test, 5);
            System.out.printf("CTR time-split bins=5 ECE before=%.4f after=%.4f%n",
                    result.getEceBefore(), result.getEceAfter());
        }
        assertTrue(result.getEceAfter() < result.getEceBefore());
        assertTrue(result.getEceAfter() < 0.15D);
    }
}
