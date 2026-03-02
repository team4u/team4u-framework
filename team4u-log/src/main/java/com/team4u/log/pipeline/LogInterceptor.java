package com.team4u.log.pipeline;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.log.core.LogEvent;

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
    default void reset() {
    }

    @Override
    default boolean supports(LogEvent event) {
        // 默认处理所有日志事件
        return true;
    }
}
