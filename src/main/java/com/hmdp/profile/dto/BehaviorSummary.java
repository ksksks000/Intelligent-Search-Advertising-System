package com.hmdp.profile.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class BehaviorSummary {

    private Long userId;
    private int days = 7;
    private int naturalEventCount;
    private int adEventCount;
    /** 自然行为标签；只来自 target_type=activity */
    private List<BehaviorTerm> naturalTerms = new ArrayList<>();
    /** 广告行为标签；只来自 target_type=ad，用于 ad_affinity */
    private List<BehaviorTerm> adTerms = new ArrayList<>();
    /** 自然行为类目计数 */
    private Map<Long, Integer> naturalCategories = new LinkedHashMap<>();
    /** 广告行为类目/计划计数 */
    private Map<String, Integer> adCategories = new LinkedHashMap<>();

    public String signature() {
        return naturalTerms.size() + ":" + adTerms.size() + ":"
                + naturalEventCount + ":" + adEventCount;
    }
}
