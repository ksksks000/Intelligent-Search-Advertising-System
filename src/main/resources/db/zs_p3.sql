-- ============================================================
-- 阶段 3：推荐 Feed 统计与行为埋点
-- 在 hmdp.sql / seckill_p3.sql / seckill_p6.sql / zs_p1.sql / zs_p1_seed.sql 之后执行
-- ============================================================

-- 1. 活动日统计表
CREATE TABLE IF NOT EXISTS `tb_activity_stats`
(
    `activity_id` BIGINT UNSIGNED NOT NULL COMMENT '活动ID',
    `stat_date`   DATE            NOT NULL COMMENT '统计日期',
    `pv`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '曝光次数',
    `uv`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '去重曝光用户数（HLL 日聚合）',
    `click_cnt`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点击次数',
    `order_cnt`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '下单次数',
    `ctr`         DECIMAL(8, 4)   NOT NULL DEFAULT 0 COMMENT '点击率 click/pv',
    `cvr`         DECIMAL(8, 4)   NOT NULL DEFAULT 0 COMMENT '转化率 order/click',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`activity_id`, `stat_date`),
    KEY `idx_stat_date` (`stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='活动推荐/行为日统计';

-- 2. 行为埋点增加 device_id（匿名兜底）
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS；执行前可先查 information_schema：
-- SELECT COUNT(*) FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA='hmdp' AND TABLE_NAME='tb_behavior_event' AND COLUMN_NAME='device_id';
ALTER TABLE `tb_behavior_event`
    ADD COLUMN `device_id` VARCHAR(64) NULL COMMENT '匿名设备ID，user_id 为空时兜底' AFTER `user_id`,
    ADD KEY `idx_device_time` (`device_id`, `event_time`);

-- 3. 推荐行为事件流（Redis）：rec:event:stream / rec:event:group / rec:event:dead
-- 4. 推荐 Feed 两级缓存：rec:feed:{userKey}:{city}:{cursor}:{size}
-- 5. 用户兴趣向量：user:interest:{userKey}，TTL 24h

-- 回滚：
-- DROP TABLE IF EXISTS tb_activity_stats;
-- ALTER TABLE tb_behavior_event DROP INDEX idx_device_time, DROP COLUMN device_id;
