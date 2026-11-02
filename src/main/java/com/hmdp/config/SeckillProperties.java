package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 秒杀库存分段配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.seckill")
public class SeckillProperties {

    /** 库存分段数上限；实际段数会按库存下调，避免出现空段 */
    private int segmentCount = 16;

    /** 消息模式：stream（默认，Lua 内原子 XADD）或 rabbit（Lua 后由应用发送） */
    private String mq = "stream";

    /** 启动时是否从 DB 预热库存分段 */
    private boolean initOnStartup = true;

    /** meta/库存 key 在活动结束后的保留时间（秒） */
    private long metaTtlBufferSeconds = 86400L;
}
