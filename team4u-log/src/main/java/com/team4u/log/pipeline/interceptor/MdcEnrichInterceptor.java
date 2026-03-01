package com.team4u.log.pipeline.interceptor;

import com.team4u.log.core.LogEvent;
import com.team4u.log.pipeline.LogInterceptor;
import org.slf4j.MDC;

/**
 * MDC 数据填充器
 * <p>
 * 从 SLF4J 的 MDC 中提取 traceId 并注入到日志事件中。
 */
public class MdcEnrichInterceptor implements LogInterceptor {

    private static final MdcEnrichInterceptor INSTANCE = new MdcEnrichInterceptor();

    private MdcEnrichInterceptor() {
    }

    public static MdcEnrichInterceptor getInstance() {
        return INSTANCE;
    }

    public void reset() {
    }

    @Override
    public int priority() {
        // 最高优先级
        return HIGH;
    }

    @Override
    public boolean handle(LogEvent event) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            event.setTraceId(traceId);
        }
        return true;
    }
}
