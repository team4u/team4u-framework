package com.team4u.framework.flow.engine;

import java.util.ArrayList;
import java.util.Objects;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.model.Outcome;

/**
 * 流程状态机运行期内存可变状态（Execution Machine State）。
 *
 * <p>封装单次流程实例的完整运行快照，包括当前活跃帧栈（{@link RuntimeFrame}）、生命周期阶段（{@link Lifecycle}）、
 * 终态四态结果（{@link Outcome}）、等待恢复的挂起点名称及待注入的外部信号等。
 * 本类非线程安全，由单个 {@link SerialMachine} 驱动线程独占。</p>
 *
 * @author jay.wu
 */
public final class MachineState {

    /**
     * 状态机生命周期枚举：
     * <ul>
     *   <li>{@link #ACTIVE}：正在活跃推进中；</li>
     *   <li>{@link #SUSPENDED}：在某个 Await 挂起点暂停等待恢复；</li>
     *   <li>{@link #COMPLETED}：已执行完成并落定最终业务四态结果；</li>
     *   <li>{@link #CANCELLED}：已被取消信号中断终止。</li>
     * </ul>
     */
    public enum Lifecycle {
        /** 推进中。 */
        ACTIVE,
        /** 异步挂起。 */
        SUSPENDED,
        /** 执行完成。 */
        COMPLETED,
        /** 已被取消。 */
        CANCELLED
    }

    public Lifecycle lifecycle = Lifecycle.ACTIVE;
    public final ArrayList<RuntimeFrame> frames;
    public final String executionId;
    public Outcome<?> outcome;
    public String awaitingPoint;
    public Object pendingSignal;
    public MachineState(PlanNode root, String executionId, Object input) {
        this.executionId = text(executionId);
        Objects.requireNonNull(input, "flow input must not be null");
        frames = new ArrayList<RuntimeFrame>();
        frames.add(new RuntimeFrame(root, input));
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "executionId must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException("executionId must not be blank");
        return value;
    }
}

