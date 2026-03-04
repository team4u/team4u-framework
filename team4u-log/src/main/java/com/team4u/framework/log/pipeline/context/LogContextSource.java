package com.team4u.framework.log.pipeline.context;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.log.core.LogEvent;

/**
 * 日志上下文寻值接口 (Pull 模型)
 * <p>
 * 基于 team4u-policy 框架实现。允许在执行动态染色、限流匹配时，
 * 按需从不同数据源（如 MDC、基础元数据、全局/线程局部属性、外部插件等）拉取数据。
 *
 * @author team4u
 */
public interface LogContextSource extends ContextPolicy<LogEvent> {

    /**
     * 根据 Key 获取值
     *
     * @param event 当前日志事件
     * @param key   规则引擎请求的 Key
     * @return 寻找到的值，若找不到返回 null
     */
    Object getValue(LogEvent event, String key);

    @Override
    default boolean supports(LogEvent event) {
        return true; // 默认对所有日志事件生效
    }

    /**
     * 优先级，越小越先执行
     */
    @Override
    default int priority() {
        return NORMAL;
    }
}
