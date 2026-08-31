package com.team4u.framework.flow.model;

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
 *   <li><b>执行器所有权绑定</b>：句柄严格绑定生成它的 {@code LocalExecutable} 实例身份，无法在不同的执行器之间跨实例恢复；</li>
 *   <li><b>挂起点一致性</b>：记录触发挂起时的 {@code resumePoint} 标识，在恢复时必须提供匹配的 {@link ResumePoint} 及类型化信号值。</li>
 * </ul>
 * </p>
 *
 * <p><b>线程交接契约</b>：本句柄可安全地跨线程移交——产生句柄的驱动线程与调用
 * {@code LocalExecutable.resume} 的恢复线程可以不同。句柄公开的 {@link #executionId()} 与
 * {@link #resumePoint()} 均为创建时快照的不可变值；挂起瞬间的引擎内部状态（帧栈、待恢复信号等）
 * 以不透明 {@link EngineState} 形式持有，恢复时由执行器通过引擎内部桥接
 * （{@code MachineState.validateResume/beginResume}）在 volatile 写之前完成 CAS 消费校验，
 * 保证恢复信号对后续驱动线程的安全发布。</p>
 *
 * @param <O> 流程最终执行完成后的输出类型
 * @author jay.wu
 */
public final class Suspension<O> {
    private final Object executableIdentity;
    private final EngineState engineState;
    /** 创建时快照的挂起点；resume 会清空引擎内部 awaitingPoint，故此字段不再读共享可变状态。 */
    private final String resumePoint;
    /** 创建时快照的执行实例标识。 */
    private final String executionId;
    private final AtomicBoolean consumed = new AtomicBoolean();

    /**
     * 挂起瞬间捕获的引擎内部状态的不透明标记接口。
     *
     * <p>实现方为流程引擎内部的状态机载体（core 引擎内为 {@code MachineState}）。
     * 本接口仅用于在不依赖 engine 包的前提下安全持有与回传内部状态，
     * 不构成对外公开的可调用契约。</p>
     */
    public interface EngineState {
    }

    /**
     * 内部构造器：捕获当前执行器身份、执行实例标识与挂起时的引擎内部状态。
     *
     * @param executableIdentity 执行器唯一身份对象
     * @param engineState        挂起时的引擎内部状态（不透明）
     * @param executionId        执行实例唯一标识
     * @param resumePoint        挂起点名称快照
     */
    public Suspension(Object executableIdentity, EngineState engineState,
                      String executionId, String resumePoint) {
        this.executableIdentity = Objects.requireNonNull(executableIdentity, "identity");
        this.engineState = Objects.requireNonNull(engineState, "engineState");
        this.executionId = text(executionId, "executionId");
        this.resumePoint = text(resumePoint, "resumePoint");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    /**
     * 获取关联的单次流程执行实例唯一标识。
     *
     * @return 执行实例 ID
     */
    public String executionId() {
        return executionId;
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
    public boolean belongsTo(Object identity) {
        return executableIdentity == identity;
    }

    /**
     * 获取创建时快照的挂起点名称。
     *
     * @return 挂起点名称
     */
    public String resumePoint() {
        return resumePoint;
    }

    /**
     * 获取挂起瞬间捕获的引擎内部状态（不透明桥接视图）。
     *
     * <p>本方法属于引擎内部桥接 API，仅供执行器层在恢复时回传内部状态使用，
     * 调用方不应依赖其具体类型或可变状态。</p>
     *
     * @return 引擎内部状态的不透明引用
     */
    public EngineState engineState() {
        return engineState;
    }

    /**
     * CAS 原子消费该挂起句柄。
     *
     * @return 若成功消费（从未消费到已消费）返回 true，若已被消费则返回 false
     */
    public boolean consume() {
        return consumed.compareAndSet(false, true);
    }
}
