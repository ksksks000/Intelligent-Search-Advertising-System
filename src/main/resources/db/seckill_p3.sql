-- ============================================================
-- P3：秒杀消费可靠性与幂等
-- 执行前请先检查历史重复订单，否则唯一索引会创建失败：
--   SELECT user_id, voucher_id, COUNT(*) AS cnt
--   FROM tb_voucher_order
--   GROUP BY user_id, voucher_id
--   HAVING cnt > 1;
-- ============================================================

-- 1. 一人一单的 DB 兜底唯一索引
ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);

-- 2. 消费幂等表：request_id 唯一 + (user_id, voucher_id) 唯一
CREATE TABLE IF NOT EXISTS tb_seckill_idempotent
(
    request_id  varchar(64)          NOT NULL COMMENT '秒杀请求ID，与订单主键一致',
    user_id     bigint(20) UNSIGNED  NOT NULL COMMENT '用户ID',
    voucher_id  bigint(20) UNSIGNED  NOT NULL COMMENT '优惠券ID',
    status      tinyint(4)           NOT NULL DEFAULT 0 COMMENT '0处理中 1成功',
    create_time timestamp            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time timestamp            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (request_id),
    UNIQUE KEY uk_user_voucher (user_id, voucher_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '秒杀消费幂等表';

-- 回滚脚本（仅在需要回退 P3 时执行）：
-- DROP TABLE IF EXISTS tb_seckill_idempotent;
-- ALTER TABLE tb_voucher_order DROP INDEX uk_user_voucher;
