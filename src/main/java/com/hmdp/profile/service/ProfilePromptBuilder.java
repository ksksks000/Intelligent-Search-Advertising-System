package com.hmdp.profile.service;

import com.hmdp.config.ProfileProperties;
import com.hmdp.profile.dto.BehaviorSummary;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 构建画像 prompt。版本号随 prompt 变更升级，落库用于回溯。
 */
@Component
public class ProfilePromptBuilder {

    @Resource
    private ProfileProperties properties;

    @Resource
    private UserBehaviorAggregator aggregator;

    public String build(BehaviorSummary summary) {
        return "你是本地生活推荐系统的用户画像生成器。只输出 JSON，不要 Markdown。\n"
                + "JSON schema:\n"
                + "{\"narrative\":\"一句话画像\","
                + "\"interest_tags\":[{\"tag\":\"火锅\",\"weight\":0.9}],"
                + "\"semantic_query\":\"用于向量召回的短语\","
                + "\"avoid_tags\":[\"标签\"],"
                + "\"ad_affinity\":[{\"tag\":\"KTV\",\"weight\":0.8}]}\n"
                + "规则：interest_tags 只能来自用户自然行为（target_type=activity）；"
                + "ad_affinity 只能来自广告行为（target_type=ad）；两者不得混用。\n"
                + "标签必须在受控词表内：" + String.join(",", ProfileVocabulary.all()) + "\n"
                + "行为摘要：\n" + aggregator.toPromptText(summary) + "\n"
                + "prompt_version=" + properties.getPromptVersion();
    }

    public String version() {
        return properties.getPromptVersion();
    }
}
