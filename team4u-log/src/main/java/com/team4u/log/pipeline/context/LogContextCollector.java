package com.team4u.log.pipeline.context;

import com.team4u.framework.policy.core.OrderedPolicyChain;
import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.log.core.LogEvent;
import com.team4u.log.pipeline.context.source.BasicMetadataSource;
import com.team4u.log.pipeline.context.source.InternalAttributesSource;
import com.team4u.log.pipeline.context.source.MdcSource;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志上下文收集器
 * <p>
 * 基于 team4u-policy 框架实现。用于在染色、限流逻辑执行前，
 * 聚合多源上下文信息。
 * <p>
 * 当前已重构为按需拉取的 Pull 模型，大幅提升了性能并减少了临时对象的产生。
 *
 * @author team4u
 */
public class LogContextCollector {

    /**
     * 有序策略链，负责寻值源的管理、排序及条件匹配
     */
    private final OrderedPolicyChain<LogEvent, LogContextSource> chain =
            new OrderedPolicyChain<>(LogContextSource.class);

    public LogContextCollector() {
        reset();
    }

    /**
     * 注册新的寻值源
     *
     * @param source 寻值源实现
     */
    public void addSource(LogContextSource source) {
        chain.register(source);
    }

    /**
     * 重置收集器，清空所有自定义属性和寻值源，恢复内置状态
     */
    public void reset() {
        chain.unregisterAll();

        // 1. 注册内置：基础信息寻值源
        chain.register(new BasicMetadataSource());

        // 2. 注册内置：MDC 寻值源 (支持高性能按 Key 查找)
        chain.register(new MdcSource());

        // 3. 自动发现：从 Java SPI (META-INF/services) 加载外部寻值源
        PolicyScanner.registerFromServiceLoader(chain);
    }

    /**
     * 收集上下文 (Pull 模型)
     *
     * @param event 日志事件
     * @return 虚拟代理 Map，只有在访问 Key 时才会执行真实的寻值逻辑
     */
    public Map<String, Object> collect(LogEvent event) {
        // 返回一个虚拟的 LookupMap，完全避免了数据拷贝和 HashMap$Node 的创建
        return new LogContextLookupMap(event, chain.allMatches(event));
    }
}
