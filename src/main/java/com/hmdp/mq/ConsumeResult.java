package com.hmdp.mq;

/**
 * Stream 消费结果。
 */
public enum ConsumeResult {
    /** 落库成功或已存在 */
    SUCCESS,
    /** 同一用户已下单（DB 唯一键兜底），消息可 ACK */
    DUPLICATE,
    /** 可重试失败，不 ACK，等待 pending 重试 */
    RETRY,
    /** 不可重试（字段缺失/超过最大重试），进入死信流 */
    DEAD
}
