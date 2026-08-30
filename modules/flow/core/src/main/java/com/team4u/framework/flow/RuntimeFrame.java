package com.team4u.framework.flow;

import java.time.Instant;
import java.util.Objects;

/**
 * 帧栈中的单个可变执行帧。entry 为进入作用域时的输入，current 随子节点推进更新。
 * phase/index 标记结构节点的进入与遍历进度；key/policyState 供控制节点使用。
 */
final class RuntimeFrame {
    final PlanNode node;
    final Object entry;
    Object current;
    // 控制节点的 policy key
    Object key;
    // PersistentPolicy 的不可变状态
    Object policyState;
    // 结构节点进入阶段（0=未进入），避免重复压栈
    int phase;
    // Sequence/Fallback 子节点遍历下标
    int index;
    // 当前重试或控制尝试次数（含首次）
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
