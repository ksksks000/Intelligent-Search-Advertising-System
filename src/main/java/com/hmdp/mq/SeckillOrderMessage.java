package com.hmdp.mq;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀订单消息体。
 * <p>
 * requestId 同时作为订单主键与消费端幂等键；segment 用于消费失败时精确回滚对应库存段。
 * {@code @JsonAlias("id")} 兼容旧版待补偿队列中 VoucherOrder 形态的 JSON。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonAlias("id")
    private Long requestId;
    private Long userId;
    private Long voucherId;
    private Integer segment;
    private Long createTime;
}
