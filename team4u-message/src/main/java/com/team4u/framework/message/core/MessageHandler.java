package com.team4u.framework.message.core;

import com.team4u.framework.policy.ContextPolicy;

import java.util.concurrent.Executor;

/**
 * 业务消息处理器
 * <p>
 * 遵循策略驱动模式，实现对特定类型消息的业务逻辑处理。
 * 实现类需通过 supportedPayloadType 声明处理的载荷类型，由框架自动完成消息与处理逻辑的路由。
 *
 * @param <T> 业务载荷的具体类型
 * @author jay.wu
 */
public interface MessageHandler<T> extends ContextPolicy<Message<?>> {

    /**
     * 获取当前处理器的专属执行器
     * <p>
     * 若返回非空，则分发引擎将优先使用该执行器运行处理逻辑；
     * 若返回为 null，则由分发引擎的全局配置决定执行策略。
     *
     * @return 独立的线程池执行器
     */
    default Executor getExecutor() {
        return null;
    }

    /**
     * 声明当前处理器支持的消息载荷类型
     * <p>
     * 通过返回具体的 Class 对象，帮助框架在运行时准确分发消息，规避泛型擦除。
     *
     * @return 业务载荷类型的 Class
     */
    Class<T> supportedPayloadType();

    /**
     * 判定当前处理器是否可以承载给定的消息
     * <p>
     * 默认通过消息载荷的类继承关系进行自动匹配。
     *
     * @param message 待处理的消息信封
     * @return true 表示该消息可被当前处理器执行
     */
    @Override
    default boolean supports(Message<?> message) {
        if (message == null || message.getPayload() == null) {
            return false;
        }
        return supportedPayloadType().isAssignableFrom(message.getPayload().getClass());
    }

    /**
     * 执行具体的消息处理业务逻辑
     * <p>
     * 在该方法中实现业务流程。任何运行时异常都将交由分发引擎进行统筹处理与拦截回调。
     *
     * @param message 完成类型适配的消息信封
     * @throws Exception 业务逻辑产生的异常
     */
    void handle(Message<T> message) throws Exception;

    /**
     * 指定当前处理器的执行优先级
     * <p>
     * 优先级数值越小，执行顺序越靠前。
     */
    @Override
    default int priority() {
        return NORMAL;
    }
}
