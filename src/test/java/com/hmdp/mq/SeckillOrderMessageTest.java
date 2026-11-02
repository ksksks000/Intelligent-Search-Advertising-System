package com.hmdp.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeckillOrderMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("兼容旧消息：id 字段映射到 requestId")
    void legacyIdAlias() throws Exception {
        SeckillOrderMessage message = mapper.readValue(
                "{\"id\":123,\"userId\":1,\"voucherId\":2}", SeckillOrderMessage.class);

        assertEquals(123L, message.getRequestId().longValue());
        assertEquals(1L, message.getUserId().longValue());
        assertEquals(2L, message.getVoucherId().longValue());
        assertNull(message.getSegment());
    }

    @Test
    @DisplayName("新消息：requestId/segment 可正常序列化与反序列化")
    void roundTrip() throws Exception {
        SeckillOrderMessage message = new SeckillOrderMessage(9L, 1L, 2L, 3, 1000L);
        String json = mapper.writeValueAsString(message);
        SeckillOrderMessage parsed = mapper.readValue(json, SeckillOrderMessage.class);

        assertEquals(message, parsed);
    }
}
