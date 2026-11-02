package com.hmdp.profile.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 画像标签受控词表：LLM 输出只保留白名单词，防止自由文本入召回。
 */
public final class ProfileVocabulary {

    private static final Set<String> TAGS = new HashSet<>(Arrays.asList(
            "火锅", "涮肉", "烤鱼", "杭帮菜", "日料", "西餐", "咖啡", "甜品",
            "KTV", "欢唱", "包厢", "酒吧", "鸡尾酒", "驻唱", "精酿",
            "健身", "瑜伽", "私教", "月卡", "夜跑", "露营", "电影",
            "SPA", "光子嫩肤", "美甲", "面部清洁", "足疗", "肩颈按摩",
            "亲子", "儿童", "手工", "烘焙", "亲子乐园", "攀爬",
            "美食", "夜生活", "周末", "聚会", "学生优惠", "新品首发"
    ));

    private ProfileVocabulary() {
    }

    public static boolean allowed(String tag) {
        return tag != null && TAGS.contains(tag.trim());
    }

    public static Set<String> all() {
        return TAGS;
    }
}
