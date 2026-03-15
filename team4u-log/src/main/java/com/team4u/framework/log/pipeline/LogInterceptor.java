package com.team4u.framework.log.pipeline;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.policy.api.ContextPolicy;

/**
 * 日志处理拦截器
 * <p>
 * 实现该接口以在日志流程中加入自定义逻辑。
 */
public interface LogInterceptor extends ContextPolicy<LogEvent> {

    /**
     * 处理日志事件
     *
     * @param event 日志事件
     * @return true 继续后续处理；false 拦截日志，终止后续处理
     */
    boolean handle(LogEvent event);

    /**
     * 重置拦截器状态
     */
    default void stop() {
    }

    /**
     * 是否需要绕过日志级别的预检查。
     * <p>
     * 用于支持会在拦截链中提升级别的拦截器，使被当前 logger 级别暂时拦住的日志
     * 仍然有机会进入引擎处理。
     */
    default boolean shouldBypassLevelPrecheck(LogEvent event) {
        return false;
    }

    @Override
    default boolean supports(LogEvent event) {
        // 默认处理所有日志事件
        return true;
    }
}
