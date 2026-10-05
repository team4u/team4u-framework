package com.team4u.framework.config.core.support;

/**
 * 配置驱动实例生命周期监听器
 * <p>
 * 监听实例初次创建、热重载替换、热重载失败及移除销毁等生命周期事件。
 * 监听回调为同步调用。为保证注册表流转的高可用性，实现方抛出的任何异常都会被框架捕获并记录警告日志，绝不阻断后续监听器分发与注册表内部状态流转。
 * </p>
 *
 * @param <T> 实例类型
 */
public interface ConfigDrivenRegistryListener<T> {

    /**
     * 实例初次创建成功事件
     *
     * @param instanceKey 实例标识
     * @param instance    新建实例
     */
    default void onInstanceCreated(String instanceKey, T instance) {
    }

    /**
     * 实例热重载替换成功事件
     *
     * @param instanceKey 实例标识
     * @param oldInstance 被替换的旧实例
     * @param newInstance 新建并生效的新实例
     */
    default void onInstanceSwapped(String instanceKey, T oldInstance, T newInstance) {
    }

    /**
     * 实例热重载失败事件
     *
     * @param instanceKey 实例标识
     * @param cause       构建异常原因
     */
    default void onInstanceSwapFailed(String instanceKey, Exception cause) {
    }

    /**
     * 实例被移除并关闭事件
     *
     * @param instanceKey 实例标识
     * @param instance    被移除并关闭的旧实例
     */
    default void onInstanceRemoved(String instanceKey, T instance) {
    }
}
