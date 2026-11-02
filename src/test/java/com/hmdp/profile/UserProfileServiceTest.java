package com.hmdp.profile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.UserProfile;
import com.hmdp.entity.BehaviorEvent;
import com.hmdp.entity.UserProfileRecord;
import com.hmdp.config.ProfileProperties;
import com.hmdp.mapper.BehaviorEventMapper;
import com.hmdp.mapper.UserProfileRecordMapper;
import com.hmdp.profile.dto.BehaviorSummary;
import com.hmdp.profile.service.UserBehaviorAggregator;
import com.hmdp.profile.service.UserProfileService;
import com.hmdp.utils.RedisConstants;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserProfileServiceTest {

    private static final long USER_ID = 999901L;
    private static HttpServer llmServer;

    @Autowired
    private UserProfileService profileService;
    @Autowired
    private UserBehaviorAggregator aggregator;
    @Autowired
    private BehaviorEventMapper behaviorEventMapper;
    @Autowired
    private UserProfileRecordMapper profileMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ProfileProperties profileProperties;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        if (llmServer == null) {
            llmServer = HttpServer.create(new InetSocketAddress(0), 0);
            llmServer.createContext("/profile", exchange -> {
                String body = "```json\n{\"narrative\":\"喜欢火锅和周末KTV的杭州用户\","
                        + "\"interest_tags\":[{\"tag\":\"火锅\",\"weight\":0.9},"
                        + "{\"tag\":\"涮肉\",\"weight\":0.8}],"
                        + "\"semantic_query\":\"火锅 涮肉 杭州\","
                        + "\"avoid_tags\":[\"美容\"],"
                        + "\"ad_affinity\":[{\"tag\":\"KTV\",\"weight\":0.7}]}\n```";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            llmServer.start();
        }
        registry.add("hmdp.profile.llm-url",
                () -> "http://127.0.0.1:" + llmServer.getAddress().getPort() + "/profile");
    }

    @BeforeEach
    void setUp() {
        cleanup();
        save(USER_ID, "activity", 900002L, "click");
        save(USER_ID, "activity", 900005L, "click");
        save(USER_ID, "ad", 1L, "click");
    }

    @AfterEach
    void cleanup() {
        behaviorEventMapper.delete(new QueryWrapper<BehaviorEvent>()
                .eq("user_id", USER_ID));
        profileMapper.deleteById("u" + USER_ID);
        redis.delete(RedisConstants.USER_PROFILE_TAGS_KEY + "u" + USER_ID + ":tags");
        redis.delete(RedisConstants.USER_PROFILE_AD_AFFINITY_KEY + "u" + USER_ID + ":ad_affinity");
        redis.delete(RedisConstants.USER_INTEREST_KEY + "u" + USER_ID);
    }

    @Test
    @DisplayName("行为聚合：自然行为与广告行为分开")
    void aggregateSeparatesNaturalAndAd() {
        BehaviorSummary summary = aggregator.aggregate(USER_ID);
        assertEquals(2, summary.getNaturalEventCount());
        assertEquals(1, summary.getAdEventCount());
        assertTrue(summary.getNaturalTerms().stream()
                .anyMatch(t -> "火锅".equals(t.getTag())));
        assertTrue(summary.getAdTerms().stream()
                .anyMatch(t -> "火锅".equals(t.getTag()) || "KTV".equals(t.getTag())));
        System.out.println("profile summary natural=" + summary.getNaturalTerms()
                + " ad=" + summary.getAdTerms());
    }

    @Test
    @DisplayName("LLM 画像成功、存储与推荐/广告消费")
    void generateAndConsume() {
        UserProfileRecord record = profileService.rebuild(USER_ID);
        assertNotNull(record);
        assertEquals("LLM", record.getSource());
        assertEquals("profile-prompt-v1", record.getPromptVersion());
        assertTrue(record.getNarrative().contains("火锅"));
        assertTrue(record.getInterestTagsJson().contains("火锅"));
        assertTrue(profileService.adAffinity("u" + USER_ID).contains("KTV"));
        UserProfile profile = profileService.enrich(UserProfile.empty(), "u" + USER_ID);
        assertTrue(profile.getTagWeights().containsKey("火锅"));
    }

    @Test
    @DisplayName("20 用户并发生成，LLM 限流 10/s 下无失败堆积")
    void concurrentGeneration() throws Exception {
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long userId = 999920L + i;
            users.add(userId);
            save(userId, "activity", 900002L, "click");
            save(userId, "ad", 1L, "click");
        }
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<UserProfileRecord>> futures = new ArrayList<>();
        for (Long userId : users) {
            futures.add(pool.submit(() -> profileService.rebuild(userId)));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        int llm = 0;
        for (Future<UserProfileRecord> future : futures) {
            UserProfileRecord record = future.get();
            assertNotNull(record);
            if ("LLM".equals(record.getSource())) {
                llm++;
            }
        }
        System.out.println("profile concurrent users=20 llm=" + llm);
        assertEquals(20, llm);
        for (Long userId : users) {
            behaviorEventMapper.delete(new QueryWrapper<BehaviorEvent>().eq("user_id", userId));
            profileMapper.deleteById("u" + userId);
            redis.delete(RedisConstants.USER_PROFILE_TAGS_KEY + "u" + userId + ":tags");
            redis.delete(RedisConstants.USER_PROFILE_AD_AFFINITY_KEY + "u" + userId + ":ad_affinity");
            redis.delete(RedisConstants.USER_INTEREST_KEY + "u" + userId);
        }
    }

    @Test
    @DisplayName("LLM 不可用时结构化降级")
    void fallbackWhenLlmUnavailable() {
        String original = profileProperties.getLlmUrl();
        try {
            profileProperties.setLlmUrl("http://127.0.0.1:1/profile");
            UserProfileRecord record = profileService.rebuild(USER_ID);
            assertEquals("STRUCTURED", record.getSource());
            assertTrue(record.getInterestTagsJson().contains("火锅"));
            assertTrue(profileService.adAffinity("u" + USER_ID).contains("火锅")
                    || profileService.adAffinity("u" + USER_ID).contains("KTV"));
        } finally {
            profileProperties.setLlmUrl(original);
        }
    }

    @Test
    @DisplayName("用户显式编辑标签")
    void userEditTags() {
        UserProfileRecord record = profileService.updateTags(USER_ID,
                Arrays.asList("火锅", "KTV", "非法标签"));
        assertTrue(record.getInterestTagsJson().contains("火锅"));
        assertFalse(record.getInterestTagsJson().contains("非法标签"));
        String redisJson = redis.opsForValue().get(
                RedisConstants.USER_PROFILE_TAGS_KEY + "u" + USER_ID + ":tags");
        assertTrue(redisJson != null && redisJson.contains("火锅"));
    }

    private void save(long userId, String targetType, long targetId, String type) {
        behaviorEventMapper.insert(new BehaviorEvent()
                .setUserId(userId)
                .setEventType(type)
                .setTargetType(targetType)
                .setTargetId(targetId)
                .setScene("ad".equals(targetType) ? "ad" : "feed")
                .setEventTime(LocalDateTime.now()));
    }
}
