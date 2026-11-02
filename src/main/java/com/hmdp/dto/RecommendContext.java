package com.hmdp.dto;

import lombok.Data;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次 Feed 请求的上下文。
 */
@Data
public class RecommendContext {

    private Long userId;
    private String deviceId;
    private String userKey;
    private String city;
    private Double x;
    private Double y;
    private int cursor;
    private int size;
    private boolean coldStart;
    private UserProfile profile;
    private float[] interestVector;
    private boolean interestVectorDegraded;
    private long startMs;

    /** 各阶段耗时（毫秒） */
    private final Map<String, Long> stageCosts = new ConcurrentHashMap<>();

    /** 多路召回可能并发写入降级标记 */
    private final Set<String> degradedSources = ConcurrentHashMap.newKeySet();

    public Map<String, Integer> tagWeights() {
        return profile == null || profile.getTagWeights() == null
                ? Collections.emptyMap() : profile.getTagWeights();
    }

    public Map<Long, Integer> categoryWeights() {
        return profile == null || profile.getCategoryWeights() == null
                ? Collections.emptyMap() : profile.getCategoryWeights();
    }
}
