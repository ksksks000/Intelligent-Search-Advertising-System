package com.hmdp.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void cleanUp() {
        MDC.remove(TraceIdFilter.TRACE_ID);
    }

    @Test
    @DisplayName("未传 traceId：自动生成、写响应头、请求结束清理 MDC")
    void generatesTraceIdAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> trace = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> trace.set(MDC.get(TraceIdFilter.TRACE_ID)));

        assertNotNull(trace.get());
        assertEquals(trace.get(), response.getHeader(TraceIdFilter.TRACE_HEADER));
        assertNull(MDC.get(TraceIdFilter.TRACE_ID));
    }

    @Test
    @DisplayName("透传上游 traceId")
    void usesIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> trace = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> trace.set(MDC.get(TraceIdFilter.TRACE_ID)));

        assertEquals("abc-123", trace.get());
        assertEquals("abc-123", response.getHeader(TraceIdFilter.TRACE_HEADER));
    }
}
