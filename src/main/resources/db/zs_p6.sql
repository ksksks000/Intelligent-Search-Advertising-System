-- ============================================================
-- 阶段 6：画像支持匿名 userKey（u{userId} / d{deviceId}）
-- 在 zs_p5.sql 之后执行。
-- ============================================================

ALTER TABLE `tb_user_profile`
    ADD COLUMN `user_key` VARCHAR(96) NULL COMMENT 'u{userId} 或 d{deviceId}' AFTER `user_id`,
    ADD COLUMN `device_id` VARCHAR(64) NULL COMMENT '匿名设备ID' AFTER `user_key`;

UPDATE `tb_user_profile`
SET `user_key` = CONCAT('u', `user_id`)
WHERE `user_key` IS NULL AND `user_id` IS NOT NULL;

ALTER TABLE `tb_user_profile`
    MODIFY COLUMN `user_id` BIGINT UNSIGNED NULL,
    MODIFY COLUMN `user_key` VARCHAR(96) NOT NULL COMMENT 'u{userId} 或 d{deviceId}',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (`user_key`),
    ADD KEY `idx_user_id` (`user_id`),
    ADD KEY `idx_device_id` (`device_id`);

-- 回滚：
-- ALTER TABLE tb_user_profile DROP PRIMARY KEY, ADD PRIMARY KEY(user_id), DROP COLUMN user_key, DROP COLUMN device_id;
