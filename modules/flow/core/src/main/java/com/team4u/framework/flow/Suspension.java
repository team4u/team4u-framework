package com.team4u.framework.flow;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local 内存模式下的单次消费型挂起续接句柄。
 *
 * <p>当流程在 Local 运行时执行遇到 {@code await(ResumePoint)} 挂起节点时，会暂停当前帧栈的执行，
 * 并将当前运行上下文封装为 {@link Suspension} 返回给调用方。
 *
 * <p>安全约束与生命周期规则：
 * <ul>
 *   <li><b>单次消费性（Single-use）</b>：句柄内部维护 CAS 原子消费标志（{@link #consumed()}），仅允许被成功恢复一次，防止并发重入或重复恢复导致状态错乱；</li>
 *   <li><b>执行器所有权绑定</b>：句柄严格绑定生成它的 {@link LocalExecutable} 实例身份，无法在不同的执行器之间跨实例恢复；</li>
 *   <li><b>挂起点一致性</b>：记录触发挂起时的 {@code resumePoint} 标识，在恢复时必须提供匹配的 {@link ResumePoint} 及类型化信号值。</li>
 * </ul>
 * </p>
 *
 * @param <O> 流程最终执行完成后的输出类型
 * @author team4u
 */
public final class Suspension<O> {
    private final Object executableIdentity;
    private final MachineState state;
    /** 创建时快照的挂起点；resume 会清空 state.awaitingPoint，故此 getter 不再读共享可变字段。 */
    private final String resumePoint;
    private final AtomicBoolean consumed = new AtomicBoolean();

    /**
     * 内部构造器：捕获当前执行器身份与挂起时的状态机。
     *
     * @param executableIdentity 执行器唯一身份对象
     * @param state              挂起时的状态机状态
     */
    Suspension(Object executableIdentity, MachineState state) {
        this.executableIdentity = Objects.requireNonNull(executableIdentity, "identity");
        this.state = Objects.requireNonNull(state, "state");
        this.resumePoint = state.awaitingPoint;
    }

    /**
     * 获取关联的单次流程执行实例唯一标识。
     *
     * @return 执行实例 ID
     */
    public String executionId() {
        return state.executionId;
    }

    /**
     * 获取当前挂起的挂起点名称（与 {@link ResumePoint#name()} 对应）。
     *
     * @return 挂起点名称
     */
    public String resumePoint() {
        return resumePoint;
    }

    /**
     * 检查当前续接句柄是否已被消费（用于 resume）。
     *
     * @return 若已被消费返回 true，否则返回 false
     */
    public boolean consumed() {
        return consumed.get();
    }

    /**
     * 校验当前句柄是否属于指定的执行器身份。
     *
     * @param identity 待比对的执行器身份
     * @return 若匹配返回 true，否则返回 false
     */
    boolean belongsTo(Object identity) {
        return executableIdentity == identity;
    }

    /**
     * 获取内部状态机状态。
     *
     * @return 状态机状态对象
     */
    MachineState state() {
        return state;
    }

    /**
     * CAS 原子消费该挂起句柄。
     *
     * @return 若成功消费（从未消费到已消费）返回 true，若已被消费则返回 false
     */
    boolean consume() {
        return consumed.compareAndSet(false, true);
    }
}

