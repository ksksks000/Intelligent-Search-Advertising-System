package com.hmdp.profile.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.ProfileProperties;
import com.hmdp.dto.UserProfile;
import com.hmdp.entity.UserProfileRecord;
import com.hmdp.mapper.UserProfileRecordMapper;
import com.hmdp.profile.dto.AiProfileResult;
import com.hmdp.profile.dto.BehaviorSummary;
import com.hmdp.profile.dto.BehaviorTerm;
import com.hmdp.profile.dto.ProfileTag;
import com.hmdp.search.embedding.EmbeddingClient;
import com.hmdp.utils.RecommendUtils;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * AI 画像生成/存储/消费。在线推荐和广告只读 Redis/DB 画像，不调用 LLM。
 */
@Slf4j
@Service
public class UserProfileService {

    private static final long PROFILE_TTL_DAYS = 7;

    @Resource
    private UserBehaviorAggregator aggregator;
    @Resource
    private ProfilePromptBuilder promptBuilder;
    @Resource
    private LlmProfileClient llmClient;
    @Resource(name = "profileLlmRateLimiter")
    private ProfileLlmRateLimiter profileRateLimiter;
    @Resource
    private ProfileProperties properties;
    @Resource
    private UserProfileRecordMapper profileMapper;
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private EmbeddingClient embeddingClient;

    public UserProfileRecord rebuild(Long userId) {
        return rebuildInternal(RecommendUtils.userKey(userId, null), userId, null);
    }

    public UserProfileRecord rebuildDevice(String deviceId) {
        return rebuildInternal(RecommendUtils.userKey(null, deviceId), null, deviceId);
    }

    public UserProfileRecord rebuild(String userKey) {
        if (userKey == null || userKey.length() < 2) {
            throw new IllegalArgumentException("userKey invalid");
        }
        if (userKey.startsWith("u")) {
            return rebuild(Long.valueOf(userKey.substring(1)));
        }
        if (userKey.startsWith("d")) {
            return rebuildDevice(userKey.substring(1));
        }
        throw new IllegalArgumentException("userKey invalid");
    }

    private UserProfileRecord rebuildInternal(String userKey, Long userId, String deviceId) {
        BehaviorSummary summary = aggregator.aggregate(userId, deviceId);
        String prompt = promptBuilder.build(summary);
        String inputHash = sha256(prompt);
        AiProfileResult result = null;
        int attempts = 0;
        while (attempts <= Math.max(0, properties.getMaxRetry())) {
            profileRateLimiter.acquireBlocking();
            result = llmClient.generate(summary, prompt);
            if (result != null) {
                break;
            }
            attempts++;
        }
        boolean llmSuccess = result != null && sanitize(result);
        if (!llmSuccess) {
            result = structured(summary);
        }
        result.setSource(llmSuccess ? "LLM" : "STRUCTURED");
        result.setPromptVersion(promptBuilder.version());
        result.setModelVersion(llmSuccess ? properties.getLlmModel() : "structured-v1");
        return save(userKey, userId, deviceId, result, inputHash);
    }

    private boolean sanitize(AiProfileResult result) {
        result.setInterestTags(filter(result.getInterestTags(), properties.getMaxTags()));
        result.setAdAffinity(filter(result.getAdAffinity(), properties.getMaxTags()));
        if (result.getSemanticQuery() == null || result.getSemanticQuery().trim().isEmpty()) {
            result.setSemanticQuery(topTags(result.getInterestTags(), 3));
        }
        return !result.getInterestTags().isEmpty();
    }

    private AiProfileResult structured(BehaviorSummary summary) {
        AiProfileResult result = new AiProfileResult();
        result.setNarrative("");
        result.setInterestTags(fromTerms(summary.getNaturalTerms()));
        result.setAdAffinity(fromTerms(summary.getAdTerms()));
        result.setSemanticQuery(topTags(result.getInterestTags(), 3));
        result.setAvoidTags(new ArrayList<>());
        return result;
    }

    private List<ProfileTag> fromTerms(List<BehaviorTerm> terms) {
        List<ProfileTag> tags = new ArrayList<>();
        for (BehaviorTerm term : terms) {
            if (ProfileVocabulary.allowed(term.getTag())) {
                tags.add(new ProfileTag(term.getTag(), term.getWeight()));
            }
            if (tags.size() >= properties.getMaxTags()) {
                break;
            }
        }
        return tags;
    }

    private List<ProfileTag> filter(List<ProfileTag> tags, int limit) {
        List<ProfileTag> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (tags == null) {
            return result;
        }
        for (ProfileTag tag : tags) {
            if (tag == null || !ProfileVocabulary.allowed(tag.getTag()) || !seen.add(tag.getTag())) {
                continue;
            }
            result.add(tag);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private String topTags(List<ProfileTag> tags, int limit) {
        return tags.stream().limit(limit).map(ProfileTag::getTag).collect(Collectors.joining(" "));
    }

    private UserProfileRecord save(String userKey, Long userId, String deviceId,
                                   AiProfileResult result, String inputHash) {
        UserProfileRecord existing = profileMapper.selectById(userKey);
        int version = existing == null || existing.getVersion() == null
                ? 1 : existing.getVersion() + 1;
        String vectorId = null;
        if (result.getSemanticQuery() != null && !result.getSemanticQuery().trim().isEmpty()) {
            try {
                float[] vector = embeddingClient.encodeQuery(result.getSemanticQuery());
                redis.opsForValue().set(RedisConstants.USER_INTEREST_KEY + userKey,
                        objectMapper.writeValueAsString(vector),
                        RedisConstants.REC_INTEREST_TTL_SECONDS, TimeUnit.SECONDS);
                vectorId = RedisConstants.USER_INTEREST_KEY + userKey;
            } catch (Exception e) {
                log.warn("画像语义向量编码失败，退化为标签召回，userKey={}, error={}",
                        userKey, e.getMessage());
            }
        }
        try {
            redis.opsForValue().set(RedisConstants.USER_PROFILE_TAGS_KEY + userKey + ":tags",
                    objectMapper.writeValueAsString(result.getInterestTags()),
                    PROFILE_TTL_DAYS, TimeUnit.DAYS);
            redis.opsForValue().set(RedisConstants.USER_PROFILE_AD_AFFINITY_KEY + userKey + ":ad_affinity",
                    objectMapper.writeValueAsString(result.getAdAffinity()),
                    PROFILE_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("写画像 Redis 失败，userKey={}, error={}", userKey, e.getMessage());
        }
        UserProfileRecord record = new UserProfileRecord()
                .setUserKey(userKey)
                .setUserId(userId)
                .setDeviceId(deviceId)
                .setNarrative(result.getNarrative())
                .setInterestTagsJson(toJson(result.getInterestTags()))
                .setAdAffinityJson(toJson(result.getAdAffinity()))
                .setAvoidTagsJson(toJson(result.getAvoidTags()))
                .setSemanticQuery(result.getSemanticQuery())
                .setSemanticVectorId(vectorId)
                .setVersion(version)
                .setSource(result.getSource())
                .setPromptVersion(result.getPromptVersion())
                .setModelVersion(result.getModelVersion())
                .setInputHash(inputHash)
                .setUpdatedAt(LocalDateTime.now());
        if (existing == null) {
            profileMapper.insert(record);
        } else {
            profileMapper.updateById(record);
        }
        log.info("用户画像生成完成 userKey={}, source={}, tags={}, adAffinity={}, version={}",
                userKey, result.getSource(), result.getInterestTags(),
                result.getAdAffinity(), version);
        return record;
    }

    public UserProfileRecord get(String userKey) {
        return profileMapper.selectById(userKey);
    }

    public UserProfileRecord get(Long userId) {
        return get(RecommendUtils.userKey(userId, null));
    }

    public Map<String, Integer> interestTags(String userKey) {
        if (userKey == null) {
            return Collections.emptyMap();
        }
        try {
            String json = redis.opsForValue().get(
                    RedisConstants.USER_PROFILE_TAGS_KEY + userKey + ":tags");
            if (json == null) {
                return Collections.emptyMap();
            }
            List<ProfileTag> tags = objectMapper.readValue(json,
                    new TypeReference<List<ProfileTag>>() {
                    });
            Map<String, Integer> result = new LinkedHashMap<>();
            for (ProfileTag tag : tags) {
                result.put(tag.getTag(), Math.max(1, (int) Math.round(tag.getWeight() * 10)));
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public Set<String> adAffinity(String userKey) {
        if (userKey == null) {
            return Collections.emptySet();
        }
        try {
            String json = redis.opsForValue().get(
                    RedisConstants.USER_PROFILE_AD_AFFINITY_KEY + userKey + ":ad_affinity");
            if (json == null) {
                return Collections.emptySet();
            }
            List<ProfileTag> tags = objectMapper.readValue(json,
                    new TypeReference<List<ProfileTag>>() {
                    });
            return tags.stream().map(ProfileTag::getTag).collect(Collectors.toSet());
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /**
     * 推荐画像增强：把阶段 5 的 interest_tags 合并进阶段 3 的 UserProfile。
     */
    public UserProfile enrich(UserProfile profile, String userKey) {
        if (profile == null) {
            return null;
        }
        Map<String, Integer> tags = interestTags(userKey);
        if (tags.isEmpty()) {
            return profile;
        }
        Map<String, Integer> merged = new LinkedHashMap<>();
        if (profile.getTagWeights() != null) {
            merged.putAll(profile.getTagWeights());
        }
        tags.forEach((tag, weight) -> merged.merge(tag, weight, Math::max));
        profile.setTagWeights(merged);
        return profile;
    }

    public UserProfileRecord updateTags(Long userId, List<String> tags) {
        return updateTags(RecommendUtils.userKey(userId, null), tags);
    }

    public UserProfileRecord updateTags(String userKey, List<String> tags) {
        List<ProfileTag> controlled = new ArrayList<>();
        for (String tag : tags) {
            if (ProfileVocabulary.allowed(tag)) {
                controlled.add(new ProfileTag(tag.trim(), 1D));
            }
        }
        UserProfileRecord record = profileMapper.selectById(userKey);
        if (record == null) {
            record = new UserProfileRecord().setUserKey(userKey).setVersion(1)
                    .setSource("USER_EDIT").setUpdatedAt(LocalDateTime.now());
            if (userKey.startsWith("u")) {
                record.setUserId(Long.valueOf(userKey.substring(1)));
            } else if (userKey.startsWith("d")) {
                record.setDeviceId(userKey.substring(1));
            }
            record.setInterestTagsJson(toJson(controlled));
            profileMapper.insert(record);
        } else {
            record.setInterestTagsJson(toJson(controlled));
            record.setVersion(record.getVersion() == null ? 1 : record.getVersion() + 1);
            record.setSource("USER_EDIT");
            record.setUpdatedAt(LocalDateTime.now());
            profileMapper.updateById(record);
        }
        try {
            redis.opsForValue().set(RedisConstants.USER_PROFILE_TAGS_KEY + userKey + ":tags",
                    toJson(controlled), PROFILE_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("写用户编辑标签失败，userKey={}", userKey, e.getMessage());
        }
        return record;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
