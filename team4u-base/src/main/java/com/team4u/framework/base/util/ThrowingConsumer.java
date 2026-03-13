package com.team4u.framework.base.util;

/**
 * 支持抛出受检异常的消费者函数式接口
 * <p>
 * 该接口是对 {@link java.util.function.Consumer} 的增强，允许在消费逻辑中直接抛出 {@link Exception}。
 * 常用于在需要 Lambda 表达式且可能涉及 IO、反射等需要处理受检异常的场景。
 * </p>
 *
 * @param <T> 参数类型
 * @author jay.wu
 */
@FunctionalInterface
public interface ThrowingConsumer<T> {

    /**
     * 执行消费逻辑
     *
     * @param t 输入参数
     * @throws Exception 可能抛出的受检异常
     */
    void accept(T t) throws Exception;
}
