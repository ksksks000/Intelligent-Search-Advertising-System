-- ============================================================
-- 阶段 1：领域模型增量脚本（在 hmdp.sql / seckill_p3.sql / seckill_p6.sql 之后执行）
-- 原则：不物理改名老表、不删老列、不建物理外键；秒杀链路零改动。
-- 新表统一 utf8mb4 / InnoDB / 无 zerofill。
-- 注意：ALTER 语句仅执行一次；重复执行会报 Duplicate column，可先查看
--       information_schema.COLUMNS 确认是否已存在。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 活动/优惠内容主表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tb_activity`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '活动ID',
    `shop_id`        BIGINT UNSIGNED NULL COMMENT '商家ID，NULL表示平台级活动',
    `title`          VARCHAR(128)    NOT NULL COMMENT '活动标题',
    `sub_title`      VARCHAR(255)    NULL COMMENT '副标题',
    `description`    TEXT            NULL COMMENT '活动详情',
    `images`         VARCHAR(2048)   NULL COMMENT '图片，逗号分隔',
    `activity_type`  TINYINT         NOT NULL DEFAULT 0 COMMENT '0优惠券 1折扣 2线下活动 3新品首发 4秒杀',
    `category_id`    BIGINT UNSIGNED NULL COMMENT '类目ID，逻辑关联 tb_shop_type.id',
    `tags`           VARCHAR(512)    NULL COMMENT '标签，逗号分隔',
    `city`           VARCHAR(64)     NULL COMMENT '城市',
    `area`           VARCHAR(128)    NULL COMMENT '商圈',
    `x`              DOUBLE          NULL COMMENT '经度',
    `y`              DOUBLE          NULL COMMENT '纬度',
    `original_price` BIGINT UNSIGNED NULL COMMENT '原价，分',
    `pay_value`      BIGINT UNSIGNED NULL COMMENT '支付金额，分',
    `actual_value`   BIGINT UNSIGNED NULL COMMENT '抵扣金额，分',
    `stock`          INT UNSIGNED    NULL COMMENT '库存/名额',
    `begin_time`     DATETIME        NULL COMMENT '开始时间',
    `end_time`       DATETIME        NULL COMMENT '结束时间',
    `status`         TINYINT         NOT NULL DEFAULT 0 COMMENT '0草稿 1待审 2在线 3下线 4过期',
    `quality_score`  INT             NOT NULL DEFAULT 0 COMMENT '质量分（排序特征）',
    `source_type`    TINYINT         NOT NULL DEFAULT 0 COMMENT '0手工 1由voucher回填 2由blog回填',
    `source_id`      BIGINT UNSIGNED NULL COMMENT '来源ID',
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source` (`source_type`, `source_id`),
    KEY `idx_shop` (`shop_id`),
    KEY `idx_category_status` (`category_id`, `status`),
    KEY `idx_status_begin` (`status`, `begin_time`),
    KEY `idx_city` (`city`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='活动/优惠内容主表';

-- ------------------------------------------------------------
-- 2. 老表增量（只 ADD，不改名、不删列）
-- ------------------------------------------------------------
ALTER TABLE `tb_shop`
    ADD COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1营业 0停业' AFTER `open_hours`,
    ADD COLUMN `tags` VARCHAR(512) NULL COMMENT '标签，逗号分隔' AFTER `status`,
    ADD COLUMN `quality_score` INT NOT NULL DEFAULT 0 COMMENT '质量分' AFTER `tags`,
    ADD KEY `idx_shop_status` (`status`);

ALTER TABLE `tb_blog`
    ADD COLUMN `activity_id` BIGINT UNSIGNED NULL COMMENT '关联活动ID' AFTER `shop_id`,
    ADD COLUMN `quality_score` INT NOT NULL DEFAULT 0 COMMENT '质量分' AFTER `activity_id`,
    ADD COLUMN `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待审 1在线 2下线' AFTER `quality_score`,
    ADD COLUMN `tags` VARCHAR(512) NULL COMMENT '标签' AFTER `status`,
    ADD KEY `idx_blog_activity` (`activity_id`),
    ADD KEY `idx_blog_status` (`status`);

ALTER TABLE `tb_voucher`
    ADD COLUMN `activity_id` BIGINT UNSIGNED NULL COMMENT '关联活动ID' AFTER `shop_id`,
    ADD KEY `idx_voucher_activity` (`activity_id`);

ALTER TABLE `tb_shop_type`
    ADD COLUMN `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父类目ID' AFTER `sort`,
    ADD COLUMN `level` TINYINT NOT NULL DEFAULT 1 COMMENT '层级' AFTER `parent_id`,
    ADD COLUMN `path` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '类目路径' AFTER `level`,
    ADD KEY `idx_category_parent` (`parent_id`);

-- ------------------------------------------------------------
-- 3. 用户行为埋点明细（本阶段只建表，不接流量）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tb_behavior_event`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NULL COMMENT '用户ID，匿名可为NULL',
    `event_type`  VARCHAR(32)     NOT NULL COMMENT 'view/search/click/like/order...',
    `target_type` VARCHAR(32)     NULL COMMENT 'activity/blog/shop',
    `target_id`   BIGINT UNSIGNED NULL COMMENT '目标ID',
    `scene`       VARCHAR(32)     NULL COMMENT 'search/feed/ad/detail',
    `trace_id`    VARCHAR(64)     NULL COMMENT '链路ID',
    `event_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间',
    `ext_json`    JSON            NULL COMMENT '扩展字段',
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `event_time`),
    KEY `idx_target` (`target_type`, `target_id`),
    KEY `idx_type_time` (`event_type`, `event_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户行为埋点明细';
