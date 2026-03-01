package com.team4u.log.pipeline.context;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.log.core.LogEvent;

import java.util.Map;

/**
 * 日志上下文贡献者
 * <p>
 * 基于 team4u-policy 框架实现。允许在执行动态染色、限流匹配前，
 * 向匹配上下文中注入额外的多维度信息（如 MDC、环境变量等）。
 */
public interface LogContextContributor extends ContextPolicy<LogEvent> {

    /**
     * 贡献上下文信息
     *
     * @param event   当前日志事件
     * @param context 待填充的上下文 Map
     */
    void contribute(LogEvent event, Map<String, Object> context);

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
