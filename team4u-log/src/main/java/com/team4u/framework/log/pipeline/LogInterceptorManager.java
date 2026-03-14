package com.team4u.framework.log.pipeline;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.interceptor.MdcEnrichInterceptor;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.framework.log.pipeline.interceptor.TargetedDyeingInterceptor;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import com.team4u.framework.policy.engine.PolicyPipeline;
import com.team4u.framework.policy.util.PolicyScanner;

/**
 * 日志拦截器管理器
 * <p>
 * 负责管理日志拦截器的初始化、执行链调度以及状态重置。
 */
public class LogInterceptorManager {

    /**
     * 策略注册表
     */
    private final OrderedPolicyChain<LogEvent, LogInterceptor> chain;
    /**
     * 拦截器执行管道
     */
    private final PolicyPipeline<LogEvent, LogInterceptor> pipeline;

    /**
     * 构造拦截器管理器并注册默认拦截器
     */
    public LogInterceptorManager() {
        // 1. 初始化有序策略链
        this.chain = new OrderedPolicyChain<>(LogInterceptor.class);

        // 2. 显式注册系统内置拦截器
        // 链路 ID 填充拦截器 (优先级: HIGH)
        chain.register(MdcEnrichInterceptor.getInstance());
        // 定向染色拦截器 (优先级: NORMAL)
        chain.register(TargetedDyeingInterceptor.getInstance());
        // 限流拦截器 (优先级: LOW)
        chain.register(RateLimitInterceptor.getInstance());

        // 3. 扫描并从 SPI 加载自定义拦截器
        PolicyScanner.registerFromServiceLoader(chain);

        this.pipeline = new PolicyPipeline<>(chain);
    }

    /**
     * 注册自定义拦截器
     *
     * @param interceptor 拦截器实例
     */
    public void register(LogInterceptor interceptor) {
        chain.register(interceptor);
    }

    /**
     * 移除拦截器
     *
     * @param interceptor 拦截器实例
     */
    public void unregister(LogInterceptor interceptor) {
        chain.unregister(interceptor);
    }

    /**
     * 执行拦截器链逻辑
     *
     * @param event 日志事件
     * @return true 继续处理；false 拦截日志，终止后续流程
     */
    public boolean execute(LogEvent event) {
        return pipeline.executeChain(event, LogInterceptor::handle);
    }

    /**
     * 判断是否存在会在处理链中改变当前输出判定的拦截器。
     */
    public boolean shouldProcessDisabledLevel(LogEvent event) {
        return chain.getPolicies().stream()
                .filter(interceptor -> interceptor.supports(event))
                .anyMatch(interceptor -> interceptor.shouldBypassLevelPrecheck(event));
    }

    /**
     * 重置所有拦截器至初始状态
     */
    public void reset() {
        chain.getPolicies().forEach(LogInterceptor::reset);
    }
}
