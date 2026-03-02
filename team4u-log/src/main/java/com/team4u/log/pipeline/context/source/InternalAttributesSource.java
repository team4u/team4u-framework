package com.team4u.log.pipeline.context.source;

import com.team4u.log.LogContext;
import com.team4u.log.core.LogEvent;
import com.team4u.log.pipeline.context.LogContextSource;

import java.util.Map;

/**
 * 内部属性寻值源 (ThreadLocal + Global)
 * <p>
 * 优先级规则：
 * 1. 优先查当前线程的局部上下文 (ThreadLocal)
 * 2. 找不到再查全局静态上下文 (Global)
 *
 * @author team4u
 */
public class InternalAttributesSource implements LogContextSource {

    @Override
    public Object getValue(LogEvent event, String key) {
        // 1. 获取当前线程的上下文属性 (请求级别数据)
        Map<String, Object> threadAttrs = LogContext.getCurrentAttributes();
        if (threadAttrs != null && threadAttrs.containsKey(key)) {
            return threadAttrs.get(key);
        }

        // 2. 获取全局静态的上下文属性 (应用级别数据，如 env, appName)
        Map<String, Object> globalAttrs = LogContext.getCollector().getGlobalAttributes();
        if (globalAttrs != null && globalAttrs.containsKey(key)) {
            return globalAttrs.get(key);
        }

        return null;
    }

    @Override
    public int priority() {
        return -150; // 中等优先级，通常高于 MDC 但低于直接指定的 Payload
    }
}
