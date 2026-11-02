package com.hmdp.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 推荐标签停用词：城市、券类型等通用标签会把不相关品类带进 TAG 召回。
 */
public final class RecommendTagUtils {

    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "杭州", "上海", "北京", "深圳", "成都",
            "代金券", "限时折扣", "线下活动", "新品首发", "限量秒杀",
            "秒杀", "优惠券", "折扣", "满减"));

    private RecommendTagUtils() {
    }

    public static boolean isGeneric(String tag) {
        return tag == null || GENERIC.contains(tag.trim());
    }
}
