package com.bones.gateway.common;

import org.slf4j.MDC;

public final class TraceIdContext {

    public static final String TRACE_ID_KEY = "traceId";

    private TraceIdContext() {
    }

    public static String getTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }
}
