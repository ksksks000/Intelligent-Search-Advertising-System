-- ============================================================
-- P6：秒杀死信/失败人工补偿表
-- ============================================================

CREATE TABLE IF NOT EXISTS tb_seckill_failure
(
    request_id  varchar(64)         NOT NULL COMMENT '秒杀请求ID',
    user_id     bigint(20) UNSIGNED NOT NULL COMMENT '用户ID',
    voucher_id  bigint(20) UNSIGNED NOT NULL COMMENT '优惠券ID',
    segment     int(11)             NOT NULL COMMENT '库存段',
    error       varchar(512)        DEFAULT NULL COMMENT '失败原因',
    status      tinyint(4)          NOT NULL DEFAULT 0 COMMENT '0待人工处理 1已自动补偿',
    create_time timestamp           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp           NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id),
    KEY idx_status_create (status, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '秒杀失败/死信补偿表';

-- 回滚：
-- DROP TABLE IF EXISTS tb_seckill_failure;
