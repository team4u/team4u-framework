package com.team4u.framework.flow.durable;
import com.team4u.framework.flow.model.Outcome;

/**
 * 持久化流执行生命周期状态枚举（Durable Execution Lifecycle）。
 *
 * <p>表示流程实例在持久化存储中的调度状态，独立于具体的业务四态结果（{@link com.team4u.framework.flow.model.Outcome}）：
 * <ul>
 *   <li>{@link #ACTIVE}：处于活跃运行或计划退避重试中；</li>
 *   <li>{@link #SUSPENDED}：在挂起点等待外部恢复信号注入；</li>
 *   <li>{@link #COMPLETED}：已完成所有编排步骤并产生最终业务四态结果（终态）；</li>
 *   <li>{@link #CANCELLED}：已被显式取消（终态）。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public enum DurableLifecycle {
    /** 活跃推进中（含等待下一次退避重试唤醒）。 */
    ACTIVE,
    /** 挂起等待外部信号注入。 */
    SUSPENDED,
    /** 执行完成并落定最终业务四态结果。 */
    COMPLETED,
    /** 已被显式取消终止。 */
    CANCELLED
}

