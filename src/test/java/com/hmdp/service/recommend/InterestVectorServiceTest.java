package com.hmdp.service.recommend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.RecommendContext;
import com.hmdp.dto.UserProfile;
import com.hmdp.search.embedding.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterestVectorServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private EmbeddingClient embeddingClient;
    private InterestVectorService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        embeddingClient = mock(EmbeddingClient.class);
        service = new InterestVectorService();
        ReflectionTestUtils.setField(service, "redis", redis);
        ReflectionTestUtils.setField(service, "embeddingClient", embeddingClient);
        ReflectionTestUtils.setField(service, "properties", new RecommendProperties());
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
    }

    @Test
    @DisplayName("embedding 服务不可用：返回 null 并标记 VECTOR 降级")
    void embeddingDownDegrades() {
        when(embeddingClient.encodeQuery(anyString()))
                .thenThrow(new EmbeddingClient.EmbeddingException("down"));
        RecommendContext context = context();

        assertNull(service.getVector(context));
        assertTrue(context.getDegradedSources().contains("VECTOR"));
        assertTrue(context.isInterestVectorDegraded());
    }

    private RecommendContext context() {
        RecommendContext context = new RecommendContext();
        context.setUserKey("u1");
        context.setProfile(UserProfile.builder().text("火锅 火锅 KTV 杭州").build());
        return context;
    }
}
