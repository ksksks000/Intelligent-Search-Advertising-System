-- ============================================================
-- 阶段 5：AI 用户画像
-- 在 zs_p4.sql 之后执行。
-- ============================================================

CREATE TABLE IF NOT EXISTS `tb_user_profile`
(
    `user_id`             BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `narrative`           TEXT            NULL COMMENT 'LLM 生成的画像叙述',
    `interest_tags_json`  JSON            NULL COMMENT '自然兴趣标签 [{tag,weight}]',
    `ad_affinity_json`    JSON            NULL COMMENT '广告偏好 [{tag,weight}]',
    `avoid_tags_json`     JSON            NULL COMMENT '回避标签',
    `semantic_query`      VARCHAR(512)    NULL COMMENT '语义查询文本',
    `semantic_vector_id`  VARCHAR(128)    NULL COMMENT '向量缓存标识 user:interest:{userKey}',
    `version`             INT             NOT NULL DEFAULT 1,
    `source`              VARCHAR(32)     NOT NULL DEFAULT 'STRUCTURED' COMMENT 'LLM/STRUCTURED',
    `prompt_version`      VARCHAR(32)     NULL,
    `model_version`       VARCHAR(64)     NULL,
    `input_hash`          VARCHAR(64)     NULL,
    `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`),
    KEY `idx_updated_at` (`updated_at`),
    KEY `idx_source` (`source`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='AI 用户画像';

-- 广告计划增加 ad_affinity 定向
ALTER TABLE `tb_ad_campaign`
    ADD COLUMN `target_affinity` VARCHAR(512) NULL COMMENT '目标 ad_affinity 标签' AFTER `target_hours`;

-- 回滚：
-- DROP TABLE IF EXISTS tb_user_profile;
-- ALTER TABLE tb_ad_campaign DROP COLUMN target_affinity;
