package com.team4u.framework.kv;

/**
 * 原生订阅能力
 * <p>
 * 实现本接口的存储可在写入/删除发生时同步分发 {@link KvEvent}，
 * 无需轮询。不支持原生通知的存储可由
 * {@code team4u-kv-lifecycle} 的 PollingWatcher 基于 {@link ScanCapable} 降级轮询。
 * </p>
 * <p>
 * 事件为尽力而为：仅保证本存储写入路径上的变更可见，
 * 不保证跨实例传播（如 Redis pub/sub 属于实现增强）。
 * </p>
 *
 * @author jay.wu
 */
public interface WatchCapable {

    /**
     * 订阅指定键空间的变更
     *
     * @param space    键空间名
     * @param listener 监听器
     * @return 关闭句柄，关闭后取消订阅
     */
    AutoCloseable watch(String space, KvListener listener);
}
