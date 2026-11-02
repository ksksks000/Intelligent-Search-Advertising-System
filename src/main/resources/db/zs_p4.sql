-- ============================================================
-- 阶段 4：广告竞价模块 DDL
-- 在 zs_p3.sql 之后执行。
-- ============================================================

CREATE TABLE IF NOT EXISTS `tb_advertiser`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(128)    NOT NULL COMMENT '广告主名称',
    `contact`     VARCHAR(64)     NULL COMMENT '联系人',
    `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '0禁用 1正常',
    `balance`     BIGINT          NOT NULL DEFAULT 0 COMMENT '账户余额，分',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='广告主';

CREATE TABLE IF NOT EXISTS `tb_ad_campaign`
(
    `id`                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `advertiser_id`             BIGINT UNSIGNED NOT NULL,
    `name`                      VARCHAR(128)    NOT NULL,
    `activity_id`               BIGINT UNSIGNED NULL COMMENT '落地活动ID',
    `ad_type`                   TINYINT         NOT NULL DEFAULT 1 COMMENT '1 CPM',
    `title`                     VARCHAR(128)    NOT NULL,
    `description`               VARCHAR(512)    NULL,
    `image`                     VARCHAR(512)    NULL,
    `landing_url`               VARCHAR(512)    NULL,
    `bid`                       BIGINT          NOT NULL DEFAULT 0 COMMENT '每次曝光出价，分',
    `total_budget`              BIGINT          NOT NULL DEFAULT 0 COMMENT '总预算，分',
    `daily_budget`              BIGINT          NOT NULL DEFAULT 0 COMMENT '日预算，分',
    `target_tags`               VARCHAR(512)    NULL COMMENT '逗号分隔标签；空=不限',
    `target_categories`         VARCHAR(256)    NULL COMMENT '逗号分隔类目ID；空=不限',
    `target_city`               VARCHAR(64)     NULL COMMENT '城市；空/ALL=不限',
    `target_device`             VARCHAR(32)     NOT NULL DEFAULT 'ALL' COMMENT 'PC/IOS/ANDROID/ALL',
    `target_hours`              VARCHAR(256)    NULL COMMENT '0-23 逗号分隔；空=全天',
    `start_time`                DATETIME        NULL,
    `end_time`                  DATETIME        NULL,
    `quality_factor`            DECIMAL(6, 4)   NOT NULL DEFAULT 1.0000 COMMENT '质量因子',
    `freq_per_day`              INT             NOT NULL DEFAULT 3 COMMENT '每人每天最多曝光次数',
    `freq_min_interval_seconds` INT             NOT NULL DEFAULT 300 COMMENT '两次曝光最小间隔秒',
    `status`                    TINYINT         NOT NULL DEFAULT 0 COMMENT '0草稿 1在线 2暂停 3结束',
    `create_time`               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_advertiser_status` (`advertiser_id`, `status`),
    KEY `idx_status_city` (`status`, `target_city`),
    KEY `idx_activity` (`activity_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='广告计划';

CREATE TABLE IF NOT EXISTS `tb_ad_billing_log`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `billing_id`    VARCHAR(96)     NOT NULL COMMENT '幂等计费ID',
    `request_id`    VARCHAR(96)     NULL COMMENT '竞价请求ID',
    `ad_id`         BIGINT UNSIGNED NOT NULL,
    `campaign_id`   BIGINT UNSIGNED NOT NULL,
    `advertiser_id` BIGINT UNSIGNED NULL,
    `user_key`      VARCHAR(96)     NULL,
    `device_id`     VARCHAR(64)     NULL,
    `ip`            VARCHAR(64)     NULL,
    `event_type`    VARCHAR(32)     NOT NULL COMMENT 'impression/click/conversion',
    `slot_id`       VARCHAR(16)     NULL COMMENT 'feed-3/feed-6',
    `bid`           BIGINT          NOT NULL DEFAULT 0,
    `cost`          BIGINT          NOT NULL DEFAULT 0 COMMENT '本次扣费，分',
    `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '0待落账 1成功 2失败',
    `event_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_billing_id` (`billing_id`),
    KEY `idx_campaign_time` (`campaign_id`, `event_time`),
    KEY `idx_user_time` (`user_key`, `event_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='广告计费流水';

CREATE TABLE IF NOT EXISTS `tb_ad_exposure_log`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `request_id`        VARCHAR(96)     NOT NULL,
    `ad_id`             BIGINT UNSIGNED NOT NULL,
    `campaign_id`       BIGINT UNSIGNED NOT NULL,
    `user_key`          VARCHAR(96)     NULL,
    `device_id`         VARCHAR(64)     NULL,
    `ip`                VARCHAR(64)     NULL,
    `slot_id`           VARCHAR(16)     NULL,
    `city`              VARCHAR(64)     NULL,
    `bid`               BIGINT          NOT NULL DEFAULT 0,
    `pctr`              DECIMAL(10, 6)  NOT NULL DEFAULT 0,
    `ecpm`              DECIMAL(14, 4)  NOT NULL DEFAULT 0,
    `exposed_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `clicked`           TINYINT         NOT NULL DEFAULT 0,
    `click_time`        DATETIME        NULL,
    `converted`         TINYINT         NOT NULL DEFAULT 0,
    `conversion_time`   DATETIME        NULL,
    `conversion_status` TINYINT         NOT NULL DEFAULT 0 COMMENT '0等待 1转化 2超时',
    `ext_json`          JSON            NULL,
    PRIMARY KEY (`id`),
    KEY `idx_campaign_exposed` (`campaign_id`, `exposed_at`),
    KEY `idx_user_exposed` (`user_key`, `exposed_at`),
    KEY `idx_request` (`request_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='广告曝光明细';

-- 演示广告主与计划（固定 id，便于本地测试；重复执行幂等）
INSERT IGNORE INTO `tb_advertiser` (`id`, `name`, `contact`, `status`, `balance`)
VALUES (1, '杭州本地生活广告主', 'demo', 1, 100000000);

INSERT IGNORE INTO `tb_ad_campaign`
(`id`, `advertiser_id`, `name`, `activity_id`, `ad_type`, `title`, `description`, `image`,
 `landing_url`, `bid`, `total_budget`, `daily_budget`, `target_tags`, `target_categories`,
 `target_city`, `target_device`, `target_hours`, `start_time`, `end_time`, `quality_factor`,
 `freq_per_day`, `freq_min_interval_seconds`, `status`)
VALUES
(1, 1, '火锅广告计划', 900002, 1, '老北京铜锅涮肉 2-3 人餐',
 '火锅/涮肉定向广告', NULL, '/activity/900002', 80, 1000000, 200000,
 '火锅,涮肉,聚餐', '1', '杭州', 'ALL', NULL,
 NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 30 DAY, 1.1000, 3, 300, 1),
(2, 1, 'KTV 广告计划', 900010, 1, '开乐迪 KTV 3 小时欢唱券',
 'KTV/欢唱定向广告', NULL, '/activity/900010', 60, 800000, 150000,
 'KTV,欢唱,包厢', '2', '杭州', 'ALL', NULL,
 NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 30 DAY, 1.0000, 3, 300, 1),
(3, 1, '美容 SPA 广告计划', 910006, 1, '拱墅光子嫩肤体验代金券',
 '美容/SPA 定向广告', NULL, '/activity/910006', 100, 1200000, 250000,
 'SPA,光子嫩肤,面部清洁', '6', '杭州', 'ALL', NULL,
 NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 30 DAY, 1.2000, 3, 300, 1),
(4, 1, '全城通投广告计划', 900018, 1, '新品桂花拿铁全城首发',
 '全城通投', NULL, '/activity/900018', 30, 500000, 100000,
 NULL, NULL, 'ALL', 'ALL', NULL,
 NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 30 DAY, 1.0000, 5, 120, 1);

-- 回滚：
-- DROP TABLE IF EXISTS tb_ad_exposure_log;
-- DROP TABLE IF EXISTS tb_ad_billing_log;
-- DROP TABLE IF EXISTS tb_ad_campaign;
-- DROP TABLE IF EXISTS tb_advertiser;
