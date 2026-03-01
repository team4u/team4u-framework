package com.team4u.log.pipeline.context;

import cn.hutool.log.Log;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.log.core.LogEvent;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志上下文收集器
 * <p>
 * 基于 team4u-policy 框架实现。用于在染色、限流逻辑执行前，
 * 聚合多源上下文信息（MDC、基础元数据、自定义插件、全局属性、当前线程局部属性）。
 */
public class LogContextCollector {

    private static final Log log = Log.get();

    /**
     * 全局静态属性 Map（如 appName, env 等）
     */
    private final Map<String, Object> globalAttributes = new ConcurrentHashMap<>();

    /**
     * 有序策略链，负责贡献者的管理、排序及条件匹配
     */
    private final OrderedPolicyChain<LogEvent, LogContextContributor> chain =
            new OrderedPolicyChain<>(LogContextContributor.class);

    public LogContextCollector() {
        reset();
    }

    /**
     * 设置全局属性
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void setGlobal(String key, Object value) {
        if (value == null) {
            globalAttributes.remove(key);
        } else {
            globalAttributes.put(key, value);
        }
    }

    /**
     * 批量设置全局属性
     *
     * @param attributes 属性 Map
     */
    public void setGlobals(Map<String, Object> attributes) {
        if (attributes != null) {
            globalAttributes.putAll(attributes);
        }
    }

    /**
     * 注册新的贡献者
     *
     * @param contributor 贡献者实现
     */
    public void addContributor(LogContextContributor contributor) {
        chain.register(contributor);
    }

    /**
     * 重置收集器，清空所有自定义属性和贡献者，恢复内置状态
     */
    public void reset() {
        globalAttributes.clear();
        chain.unregisterAll();

        // 1. 注册内置：全局静态属性 (优先级最高)
        chain.register(new GlobalAttributesContributor());

        // 2. 注册内置：当前线程局部属性 (优先级次之，可覆盖全局)
        chain.register(new CurrentThreadAttributesContributor());

        // 3. 注册内置：基础信息贡献者
        chain.register(new BasicMetadataContributor());

        // 4. 注册内置：全量 MDC 信息贡献者
        chain.register(new FullMdcContributor());

        // 5. 自动发现：从 Java SPI (META-INF/services) 加载外部贡献者
        PolicyScanner.registerFromServiceLoader(chain);
    }

    /**
     * 收集全部上下文 Map
     *
     * @param event 日志事件
     * @return 匹配上下文
     */
    public Map<String, Object> collect(LogEvent event) {
        Map<String, Object> context = new HashMap<>(event.getPayload());

        // 框架会自动根据 supports(event) 过滤，并按 priority() 排序
        for (LogContextContributor contributor : chain.allMatches(event)) {
            try {
                contributor.contribute(event, context);
            } catch (Exception e) {
                // 隔离保护：单个贡献者异常不影响染色主流程
                log.error("LogContextCollector|collect|error|msg={}", e.getMessage());
            }
        }

        return context;
    }

    /**
     * 内置贡献者：当前线程局部属性
     */
    private static class CurrentThreadAttributesContributor implements LogContextContributor {
        @Override
        public void contribute(LogEvent event, Map<String, Object> context) {
            Map<String, Object> threadAttrs = com.team4u.log.LogContext.getCurrentAttributes();
            if (threadAttrs != null && !threadAttrs.isEmpty()) {
                context.putAll(threadAttrs);
            }
        }

        @Override
        public int priority() {
            return -150; // 介于全局和基础信息之间
        }
    }

    /**
     * 内置贡献者：基础元数据
     */
    private static class BasicMetadataContributor implements LogContextContributor {
        @Override
        public void contribute(LogEvent event, Map<String, Object> context) {
            context.put("action", event.getAction());
            context.put("level", event.getLevel() != null ? event.getLevel().name() : "UNKNOWN");
            context.put("logger", event.getLoggerName());
            context.put("thread", Thread.currentThread().getName());
            context.put("status", event.getStatus());
            if (event.getDurationMs() >= 0) {
                context.put("durationMs", event.getDurationMs());
            }
        }

        @Override
        public int priority() {
            return -100;
        }
    }

    /**
     * 内置贡献者：全量 MDC 信息
     */
    private static class FullMdcContributor implements LogContextContributor {
        @Override
        public void contribute(LogEvent event, Map<String, Object> context) {
            Map<String, String> mdcMap = MDC.getCopyOfContextMap();
            if (mdcMap != null && !mdcMap.isEmpty()) {
                // 将整个 MDC Map 注入到 'mdc' 键下，支持规则通过 'mdc.key' 进行嵌套访问
                context.put("mdc", mdcMap);
            }
        }

        @Override
        public int priority() {
            return -90;
        }
    }

    /**
     * 内置贡献者：全局静态属性
     */
    private class GlobalAttributesContributor implements LogContextContributor {
        @Override
        public void contribute(LogEvent event, Map<String, Object> context) {
            context.putAll(globalAttributes);
        }

        @Override
        public int priority() {
            return -200; // 优先级最高，允许被后续动态信息覆盖
        }
    }
}
