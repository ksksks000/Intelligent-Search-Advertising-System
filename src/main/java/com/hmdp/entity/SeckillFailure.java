package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 秒杀失败/死信人工补偿表。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_failure")
public class SeckillFailure implements Serializable {

    public static final int PENDING = 0;
    public static final int COMPENSATED = 1;

    private static final long serialVersionUID = 1L;

    @TableId(value = "request_id", type = IdType.INPUT)
    private String requestId;

    private Long userId;

    private Long voucherId;

    private Integer segment;

    private String error;

    /** 0 待人工处理；1 已自动补偿 */
    private Integer status;
}
