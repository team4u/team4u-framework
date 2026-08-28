package com.team4u.framework.log.pipeline.interceptor;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.LogInterceptor;
import org.slf4j.MDC;

/**
 * 链路 ID 填充拦截器 (MdcEnrichInterceptor)
 * <p>
 * 从 SLF4J 的 MDC 中提取指定的链路追踪 ID，并注入到日志事件的最外层。
 */
public class MdcEnrichInterceptor implements LogInterceptor {

    private static final MdcEnrichInterceptor INSTANCE = new MdcEnrichInterceptor();

    /**
     * 提取 traceId 的 MDC 键名，默认为 "traceId"
     */
    private volatile String traceIdKey = "traceId";

    private MdcEnrichInterceptor() {
    }

    /**
     * 获取兼容旧 API 的共享实例。新引擎默认使用 {@link #create()} 创建独立实例。
     *
     * @return MdcEnrichInterceptor 实例
     */
    public static MdcEnrichInterceptor getInstance() {
        return INSTANCE;
    }

    public static MdcEnrichInterceptor create() {
        return new MdcEnrichInterceptor();
    }

    /**
     * 自定义 TraceId 的提取键名
     *
     * @param traceIdKey 键名（例如 "requestId"）
     */
    public void setTraceIdKey(String traceIdKey) {
        if (traceIdKey != null && !traceIdKey.trim().isEmpty()) {
            this.traceIdKey = traceIdKey;
        }
    }

    @Override
    public void stop() {
        this.traceIdKey = "traceId";
    }

    @Override
    public int priority() {
        // 最高优先级，确保后续拦截器能够基于提出来的 traceId 进行逻辑处理
        return HIGH;
    }

    @Override
    public boolean handle(LogEvent event) {
        String traceId = MDC.get(traceIdKey);
        if (traceId != null && !traceId.trim().isEmpty()) {
            event.setTraceId(traceId);
        }
        return true;
    }
}
