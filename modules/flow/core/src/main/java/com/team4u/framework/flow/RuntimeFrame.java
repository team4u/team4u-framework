package com.team4u.framework.flow;

import java.time.Instant;
import java.util.Objects;

/**
 * 流程运行时堆栈中的单个可变执行帧（Runtime Stack Frame）。
 *
 * <p>用于跟踪单个 AST 物理节点在执行期间的瞬态上下文：
 * <ul>
 *   <li>{@code node}：关联的物理执行计划节点；</li>
 *   <li>{@code entry}：首次进入该作用域时的不可变原始输入载荷（供降级恢复与重试复用）；</li>
 *   <li>{@code current}：当前子步骤传递推进的数据载荷；</li>
 *   <li>{@code key} / {@code policyState}：控制节点投影出的策略键与持久化策略状态机；</li>
 *   <li>{@code phase} / {@code index}：结构节点的阶段标记与多分支/流水线步进指针；</li>
 *   <li>{@code attempt}：当前重试或评估轮次；</li>
 *   <li>{@code wake} / {@code deadline}：计划退避唤醒时刻与超时截止时刻。</li>
 * </ul>
 * </p>
 *
 * @author team4u
 */
final class RuntimeFrame {
    final PlanNode node;
    final Object entry;
    Object current;
    /** 控制节点的 policy key。 */
    Object key;
    /** PersistentPolicy 的不可变状态。 */
    Object policyState;
    /** 结构节点进入阶段（0=未进入），避免重复压栈。 */
    int phase;
    /** Sequence/Fallback 子节点遍历下标。 */
    int index;
    /** 当前重试或控制尝试次数（从 1 开始）。 */
    int attempt = 1;
    Instant wake;
    Instant deadline;
    String selected;
    boolean observerStarted;

    RuntimeFrame(PlanNode node, Object entry) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.entry = Objects.requireNonNull(entry, "entry must not be null");
        this.current = entry;
    }
}

