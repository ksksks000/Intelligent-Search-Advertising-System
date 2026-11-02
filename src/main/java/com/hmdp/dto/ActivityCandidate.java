package com.hmdp.dto;

import com.hmdp.entity.Activity;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 召回候选：同一活动可来自多路，保留每路分数用于粗排加权。
 */
@Data
public class ActivityCandidate {

    private Long activityId;
    private Map<String, Double> sourceScores = new LinkedHashMap<>();
    private double recallScore;
    private double coarseScore;
    private double fineScore;
    private Activity activity;

    public void addSource(String source, double score) {
        if (source == null) {
            return;
        }
        double safe = Math.max(0D, Math.min(1D, score));
        sourceScores.merge(source, safe, Math::max);
    }

    public static ActivityCandidate of(Long activityId, String source, double score) {
        ActivityCandidate candidate = new ActivityCandidate();
        candidate.setActivityId(activityId);
        candidate.addSource(source, score);
        candidate.setRecallScore(Math.max(0D, Math.min(1D, score)));
        return candidate;
    }
}
