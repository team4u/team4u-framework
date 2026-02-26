package com.team4u.framework.message.core.interceptor;

import com.team4u.framework.message.core.Message;
import com.team4u.framework.policy.api.ContextPolicy;

/**
 * 消息处理拦截器
 * <p>
 * 为消息处理链路提供切面扩展能力。支持在消息分发前进行阻断，
 * 或在分发后（无论成功或失败）进行监控统计、资源释放等通用逻辑的处理。
 *
 * @author jay.wu
 */
public interface MessageInterceptor extends ContextPolicy<Message<?>> {

    /**
     * 定义拦截器匹配规则，默认对所有消息生效
     */
    @Override
    default boolean supports(Message<?> message) {
        return true;
    }

    /**
     * 前置处理逻辑
     * <p>
     * 在消息交付给具体业务处理器之前调用。
     *
     * @param message 待处理的消息
     * @return 返回 true 继续链路，返回 false 则中止后续处理
     */
    default boolean preHandle(Message<?> message) {
        return true;
    }

    /**
     * 后置处理逻辑
     * <p>
     * 在所有业务处理器成功执行完毕后触发。若处理过程中产生未捕获异常，则跳过该环节。
     *
     * @param message 完成处理的消息对象
     */
    default void postHandle(Message<?> message) {
    }

    /**
     * 完成阶段处理逻辑
     * <p>
     * 无论业务执行成功与否均会触发的回调，类似于程序的 finally 块。
     * 用于执行最终的链路清理、结果审计或异常感知。
     *
     * @param message 消息对象
     * @param ex      业务链条执行过程中产生的异常，若执行顺畅则为 null
     */
    default void afterCompletion(Message<?> message, Exception ex) {
    }
}
