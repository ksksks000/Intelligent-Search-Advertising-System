package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀消费幂等表：request_id 唯一，兼做 (user_id, voucher_id) 一人一单的 DB 兜底。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_idempotent")
public class SeckillIdempotent implements Serializable {

    public static final int PROCESSING = 0;
    public static final int SUCCESS = 1;

    private static final long serialVersionUID = 1L;

    @TableId(value = "request_id", type = IdType.INPUT)
    private String requestId;

    private Long userId;

    private Long voucherId;

    /** 0 处理中；1 成功 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
