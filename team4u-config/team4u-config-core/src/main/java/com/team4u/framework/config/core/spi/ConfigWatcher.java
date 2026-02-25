package com.team4u.framework.config.core.spi;

import com.team4u.framework.policy.OrderedPolicy;

/**
 * 配置变更监听器 (触发器)
 * <p>
 * 负责“发现”变化（如文件变更、数据库定时轮询），并向管理层面发送重载信号，而不是直接处理数据加载。
 */
public interface ConfigWatcher extends OrderedPolicy {

    /**
     * 初始化 Watcher 资源
     * <p>
     * (如建立数据库长连接、启动定时线程等)。在 ConfigManager 启动或注册此 Watcher 时被调用。
     */
    default void init() {
    }

    /**
     * 注册监听回调，当源数据有变更嫌疑时调用 changeSignal 以触发热更新
     *
     * @param changeSignal 变更信号触发器回调函数
     */
    void watch(Runnable changeSignal);

    /**
     * 销毁并释放资源
     * <p>
     * (如关闭文件句柄、停止轮询线程)。在框架生命周期结束或主动移除该 Watcher 时调用。
     */
    default void destroy() {
    }
}
