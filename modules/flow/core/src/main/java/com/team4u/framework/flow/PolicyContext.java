package com.team4u.framework.flow;

/**
 * 策略（{@link Policy} / {@link PersistentPolicy}）回调可见的只读执行上下文接口。
 *
 * <p>包含以下要素：
 * <ul>
 *   <li>{@link #metadata()}：当前节点的拓扑元数据；</li>
 *   <li>{@link #attempt()}：当前作用域的执行尝试轮次（从 1 开始递增计数）；</li>
 *   <li>{@link #cancellation()}：协作式取消信号。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public interface PolicyContext {

    /**
     * 获取当前策略作用域节点的元数据描述。
     *
     * @return 节点元数据 {@link Metadata}，保证非 null
     */
    Metadata metadata();

    /**
     * 获取当前步骤当前的尝试轮次计数。
     *
     * <p>初次尝试为 1，随后每次重试递增 1。</p>
     *
     * @return 尝试次数（>= 1）
     */
    int attempt();

    /**
     * 获取当前流程的协作式取消信号。
     *
     * @return 取消信号 {@link Cancellation.Signal}，保证非 null
     */
    Cancellation.Signal cancellation();
}

