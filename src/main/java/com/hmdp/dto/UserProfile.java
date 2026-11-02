package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 用户兴趣画像：近期活动、标签权重、类目权重、拼接文本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    private List<Long> activityIds;
    private Map<String, Integer> tagWeights;
    private Map<Long, Integer> categoryWeights;
    /** 近 7 天点击次数按类目聚合 */
    private Map<Long, Integer> categoryClickCounts;
    /** 近 7 天点击总次数 */
    private int totalClicks;
    private String text;

    public boolean isEmpty() {
        return (activityIds == null || activityIds.isEmpty())
                && (tagWeights == null || tagWeights.isEmpty())
                && (categoryWeights == null || categoryWeights.isEmpty());
    }

    public static UserProfile empty() {
        return new UserProfile(Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), 0, "");
    }
}
