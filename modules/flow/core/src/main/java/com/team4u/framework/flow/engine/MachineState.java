package com.team4u.framework.flow.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Suspension;
/**
 * 流程状态机运行期内存可变状态（Execution Machine State）。
 *
 * <p>封装单次流程实例的完整运行快照，包括当前活跃帧栈（{@link RuntimeFrame}）、生命周期阶段（{@link Lifecycle}）、
 * 终态四态结果（{@link Outcome}）、等待恢复的挂起点名称及待注入的外部信号等。
 * 本类属于引擎内部状态载体，字段全部私有，仅限 engine 包内通过包私有访问器读写。</p>
 *
 * <p><b>线程模型</b>：状态推进本身由单个 {@link SerialMachine} 驱动线程独占，非线程安全；
 * 但 {@code lifecycle}、{@code pendingSignal} 与 {@code awaitingPoint} 声明为 {@code volatile}，
 * 以支持跨线程 resume 时的安全发布——恢复线程通过 {@link #beginResume} 写入信号后，
 * 后续驱动线程能够立即观察到生命周期与信号的可见性。</p>
 *
 * @author jay.wu
 */
public final class MachineState implements Suspension.EngineState {

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

    private volatile Lifecycle lifecycle = Lifecycle.ACTIVE;
    private final ArrayList<RuntimeFrame> frames;
    private final String executionId;
    private volatile Outcome<?> outcome;
    private volatile String awaitingPoint;
    private volatile Object pendingSignal;
    /** 帧栈最小 deadline 缓存（O(1) 查询，见 {@link #earliestDeadline()}）。 */
    private Instant minDeadline;
    /** 缓存脏标记：任何携带 deadline 的帧增删后置位，读取时惰性重算。 */
    private boolean deadlineDirty;

    /**
     * 构造状态机运行状态。
     *
     * @param root        执行计划根节点，不能为 null
     * @param executionId 执行实例唯一标识，不能为 null 或空白
     * @param input       流程输入数据，不能为 null
     */
    public MachineState(PlanNode root, String executionId, Object input) {
        this.executionId = text(executionId);
        Objects.requireNonNull(input, "flow input must not be null");
        frames = new ArrayList<RuntimeFrame>();
        frames.add(new RuntimeFrame(root, input));
    }

    /**
     * 压入新的执行帧（帧构造时不携带 deadline，缓存无需失效）。
     *
     * @param frame 新帧，不能为 null
     */
    void pushFrame(RuntimeFrame frame) {
        frames.add(Objects.requireNonNull(frame, "frame must not be null"));
    }

    /**
     * 弹出栈顶执行帧；若被弹出帧携带 deadline 则将缓存置脏。
     *
     * @return 被弹出的栈顶帧
     */
    RuntimeFrame popFrame() {
        RuntimeFrame removed = frames.remove(frames.size() - 1);
        if (removed.deadline != null) {
            deadlineDirty = true;
        }
        return removed;
    }

    /** 清空全部活跃帧（取消路径），缓存同步置脏（包内可见）。 */
    void clearFrames() {
        frames.clear();
        deadlineDirty = true;
    }

    /**
     * 为帧设置超时截止时间并同步维护最小 deadline 缓存（包内可见）。
     *
     * @param frame    目标帧
     * @param deadline 截止时间，不能为 null
     */
    void deadlineBound(RuntimeFrame frame, Instant deadline) {
        Objects.requireNonNull(deadline, "deadline must not be null");
        frame.deadline = deadline;
        if (minDeadline == null || deadline.isBefore(minDeadline)) {
            minDeadline = deadline;
        }
    }

    /**
     * 清除帧的超时截止时间（归约完成）并将缓存置脏（包内可见）。
     *
     * @param frame 目标帧
     */
    void deadlineCleared(RuntimeFrame frame) {
        if (frame.deadline != null) {
            frame.deadline = null;
            deadlineDirty = true;
        }
    }

    /**
     * 查询当前帧栈中最早（最小）的 deadline（包内可见，O(1) 均摊）。
     *
     * <p>缓存策略：无任何 TIMEOUT 作用域时直接返回 null（O(1)）；
     * 携带 deadline 的帧增删后置脏，读取时惰性全栈重算一次。绝大多数帧
     * （非 TIMEOUT）的压弹栈不触发重算，深嵌套场景无 O(n²) 回归。</p>
     *
     * @return 最早截止时间，无则返回 null
     */
    Instant earliestDeadline() {
        if (deadlineDirty) {
            Instant result = null;
            for (RuntimeFrame frame : frames) {
                if (frame.deadline != null
                        && (result == null || frame.deadline.isBefore(result))) {
                    result = frame.deadline;
                }
            }
            minDeadline = result;
            deadlineDirty = false;
        }
        return minDeadline;
    }

    /**
     * 恢复桥接（第一步：校验）：检查引擎状态处于挂起态且挂起点名称匹配。
     *
     * <p>本方法属于引擎内部桥接 API，仅供执行器层（如 LocalExecutable）在跨线程 resume
     * 时调用；对引擎外部不构成稳定公开契约。校验通过后再执行 CAS 消费与信号注入，
     * 保证错误的挂起点不会消耗句柄的单次消费机会。</p>
     *
     * @param engineState 挂起时捕获的引擎内部状态，不能为 null
     * @param pointName   恢复挂起点名称，不能为 null
     * @return 已通过校验的状态机状态
     * @throws IllegalStateException    当内部状态并非挂起态时抛出
     * @throws IllegalArgumentException 当挂起点名称与内部状态不匹配时抛出
     */
    public static MachineState validateResume(Suspension.EngineState engineState,
                                               String pointName) {
        Objects.requireNonNull(engineState, "engineState must not be null");
        Objects.requireNonNull(pointName, "pointName must not be null");
        if (!(engineState instanceof MachineState)) {
            throw new IllegalStateException("Unknown engine state: " + engineState.getClass());
        }
        MachineState state = (MachineState) engineState;
        if (state.lifecycle != Lifecycle.SUSPENDED) {
            throw new IllegalStateException("Suspension is not suspended");
        }
        if (!pointName.equals(state.awaitingPoint)) {
            throw new IllegalArgumentException("ResumePoint does not match suspension");
        }
        return state;
    }

    /**
     * 恢复桥接（第二步：注入）：将生命周期回置 ACTIVE 并写入恢复信号（volatile 写，安全发布）。
     *
     * <p>仅应在 {@link #validateResume} 校验通过且句柄 CAS 消费成功后调用。</p>
     *
     * @param signal 恢复信号数据，不能为 null
     */
    public void beginResume(Object signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        pendingSignal = signal;
        lifecycle = Lifecycle.ACTIVE;
    }

    /** 获取当前生命周期阶段（包内可见）。 */
    Lifecycle lifecycle() {
        return lifecycle;
    }

    /** 覆写生命周期阶段（包内可见）。 */
    void lifecycle(Lifecycle value) {
        this.lifecycle = value;
    }

    /** 获取活跃帧栈（包内可见，返回可变内部列表，仅供引擎推进使用）。 */
    List<RuntimeFrame> frames() {
        return frames;
    }

    /** 获取执行实例唯一标识（包内可见）。 */
    String executionId() {
        return executionId;
    }

    /** 获取终态四态结果（包内可见）。 */
    Outcome<?> outcome() {
        return outcome;
    }

    /** 覆写终态四态结果（包内可见）。 */
    void outcome(Outcome<?> value) {
        this.outcome = value;
    }

    /** 获取等待恢复的挂起点名称（包内可见）。 */
    String awaitingPoint() {
        return awaitingPoint;
    }

    /** 覆写等待恢复的挂起点名称（包内可见）。 */
    void awaitingPoint(String value) {
        this.awaitingPoint = value;
    }

    /** 获取待注入的恢复信号（包内可见）。 */
    Object pendingSignal() {
        return pendingSignal;
    }

    /** 覆写待注入的恢复信号（包内可见）。 */
    void pendingSignal(Object value) {
        this.pendingSignal = value;
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "executionId must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException("executionId must not be blank");
        return value;
    }
}
