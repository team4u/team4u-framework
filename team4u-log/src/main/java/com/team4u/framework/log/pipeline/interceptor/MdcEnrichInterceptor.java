package com.team4u.framework.log.pipeline.interceptor;

import cn.hutool.core.util.StrUtil;
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
     * 获取链路 ID 填充拦截器单例实例
     *
     * @return MdcEnrichInterceptor 实例
     */
    public static MdcEnrichInterceptor getInstance() {
        return INSTANCE;
    }

    /**
     * 自定义 TraceId 的提取键名
     *
     * @param traceIdKey 键名（例如 "requestId"）
     */
    public void setTraceIdKey(String traceIdKey) {
        if (StrUtil.isNotBlank(traceIdKey)) {
            this.traceIdKey = traceIdKey;
        }
    }

    @Override
    public void reset() {
        this.traceIdKey = "traceId";
    }

    @Override
    public int priority() {
        // 最高优先级，确保后续拦截器能够基于提出来的 traceId 进行逻辑处理
        return HIGH;
    }

    @Override
    public boolean handle(LogEvent event) {
        // 直接从 MDC 中获取指定的键值
        String traceId = MDC.get(traceIdKey);
        if (StrUtil.isNotBlank(traceId)) {
            event.setTraceId(traceId);
        }
        return true;
    }
}
