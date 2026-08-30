package com.team4u.framework.config.core.spi;

import com.team4u.framework.policy.api.OrderedPolicy;

/**
 * 配置变更监听器 SPI 接口
 * <p>
 * 负责监控配置源的变化（例如文件系统变动、数据库轮询等），并向配置中心发送重载信号。
 * 监听器不负责具体的数据加载，仅作为触发热更新的“触发器”。
 * </p>
 */
public interface ConfigWatcher extends OrderedPolicy {

    /**
     * 初始化监听器资源
     * <p>
     * 在配置中心启动或注册此监听器时调用。用于建立数据库连接、启动线程池或注册文件钩子等。
     * </p>
     */
    default void init() {
    }

    /**
     * 开启监控并注册回调信号
     *
     * @param changeSignal 当发现变更嫌疑时，应执行此回调函数以触发快照重载
     */
    void watch(Runnable changeSignal);

    /**
     * 释放监听器占用的资源
     * <p>
     * 在系统关闭或移除监听器时调用。用于关闭连接、停止线程或取消钩子注册。
     * </p>
     */
    default void destroy() {
    }
}
