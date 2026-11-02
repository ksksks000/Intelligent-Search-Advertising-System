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

class AdCtrCalibrationServiceTest {

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
    @DisplayName("保序回归校准后 ECE 收敛")
    void calibrationReducesEce() {
        List<AdCtrSample> samples = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            samples.add(new AdCtrSample(0.8D, i < 100 ? 1 : 0)); // 真实 20%
        }
        for (int i = 0; i < 500; i++) {
            samples.add(new AdCtrSample(0.2D, i < 50 ? 1 : 0));  // 真实 10%
        }
        AdCtrCalibrationService.FitResult result = service.fit(samples);
        System.out.printf("CTR ECE before=%.4f after=%.4f samples=%d%n",
                result.getEceBefore(), result.getEceAfter(), result.getSamples());
        assertTrue(result.getEceAfter() < result.getEceBefore());
        assertTrue(result.getEceAfter() < 0.05D);
    }
}
