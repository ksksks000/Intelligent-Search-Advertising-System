-- ============================================================
-- 阶段 1 回滚脚本（会删除 tb_activity / tb_behavior_event 及其数据，慎用）
-- 执行顺序：先删索引，再删列；最后删表。
-- ============================================================

ALTER TABLE `tb_shop`
    DROP INDEX `idx_shop_status`,
    DROP COLUMN `quality_score`,
    DROP COLUMN `tags`,
    DROP COLUMN `status`;

ALTER TABLE `tb_blog`
    DROP INDEX `idx_blog_activity`,
    DROP INDEX `idx_blog_status`,
    DROP COLUMN `tags`,
    DROP COLUMN `status`,
    DROP COLUMN `quality_score`,
    DROP COLUMN `activity_id`;

ALTER TABLE `tb_voucher`
    DROP INDEX `idx_voucher_activity`,
    DROP COLUMN `activity_id`;

ALTER TABLE `tb_shop_type`
    DROP INDEX `idx_category_parent`,
    DROP COLUMN `path`,
    DROP COLUMN `level`,
    DROP COLUMN `parent_id`;

DROP TABLE IF EXISTS `tb_behavior_event`;
DROP TABLE IF EXISTS `tb_activity`;
