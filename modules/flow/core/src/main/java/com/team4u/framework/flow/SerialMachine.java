package com.team4u.framework.flow;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;

/**
 * 同步单线程堆栈推进执行引擎内核（Serial Execution State Machine）。
 *
 * <p>架构与设计原则：
 * <ul>
 *   <li><b>无栈递归状态机</b>：通过在堆上显式维护 {@link RuntimeFrame} 栈结构驱动 AST 节点展开与归约，彻底消除 JVM 方法调用栈溢出风险；</li>
 *   <li><b>线程独占与非线程安全</b>：单次 {@link #drive()} 调用独占当前线程推进状态机，状态推进期间外部只能通过 {@link Cancellation} 注入取消信号；</li>
 *   <li><b>取消/超时协同</b>：每帧循环优先检测取消标志与作用域截止时间（Deadline），支持精确定位并终止最内层超时作用域；</li>
 *   <li><b>四态结果逐层归约</b>：子节点产生 Outcome 后弹出当前帧，由 {@link FrameReducer} 驱动父帧决策（如 Sequence 下一步、Fallback 下一分支、Retry 重试或冒泡向上传播）。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
final class SerialMachine {
    private final String flowId;
    private final int flowVersion;
    private final MachineState state;
    private final Cancellation cancellation;
    private final FlowObserver observer;
    private final InvocationRunner invocations;
    private final CallbackRunner callbacks;
    private final MachineObserver events;
    private final MachineCancellationCoordinator cancellationCoordinator;
    private final ExecutorService executor;

    SerialMachine(PlanNode root, String flowId, int flowVersion, MachineState state,
                  Cancellation cancellation, FlowObserver observer, ExecutorService executor) {
        this.flowId = text(flowId, "flowId");
        this.flowVersion = flowVersion;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.executor = executor;
        invocations = new InvocationRunner(flowId, flowVersion, state.executionId,
                cancellation, observer, executor);
        callbacks = new CallbackRunner(cancellation, executor);
        events = new MachineObserver(flowId, flowVersion, state, observer);
        cancellationCoordinator = new MachineCancellationCoordinator(state, cancellation);
    }

    /**
     * 同步驱动推进帧栈，直至到达终态（COMPLETED）、挂起等待外部信号（SUSPENDED）、收到取消信号（CANCELLED）或超时。
     *
     * @return 状态机推进结果 {@link MachineResult}
     */
    MachineResult drive() {

        if (state.lifecycle != MachineState.Lifecycle.ACTIVE)
            return MachineResult.from(state, wakeAt());
        Thread thread = Thread.currentThread();
        // 记录进入时的中断状态
        boolean interrupted = thread.isInterrupted();
        cancellation.attach(thread);
        try {
            while (state.lifecycle == MachineState.Lifecycle.ACTIVE) {
                // 每帧优先检查取消与截止时间，保证取消/超时及时生效
                if (cancellation.isCancelled()) {
                    cancel();
                    break;
                }
                if (expiredDeadline()) {
                    timeoutNearestScope();
                    continue;
                }
                RuntimeFrame frame = state.frames.get(state.frames.size() - 1);
                PlanNode node = frame.node;
                events.nodeStarted(frame);
                if (node instanceof PlanNode.Invoke) {
                    PlanNode.Invoke invoke = (PlanNode.Invoke) node;
                    Outcome<?> outcome;
                    try {
                        outcome = invocations.invoke(invoke, frame.entry, deadline());
                    } catch (CancellationException cancelled) {
                        if (cancellation.isCancelled()) {
                            cancel();
                            break;
                        }
                        outcome = Outcome.failed(Failure.of(
                                "OPERATION_CANCELLED", "Operation was cancelled"));
                    }
                    finish(outcome);
                } else if (node instanceof PlanNode.Sequence) {
                    FrameEntrant.sequence(this, frame, (PlanNode.Sequence) node);
                } else if (node instanceof PlanNode.Route) {
                    FrameEntrant.route(this, frame, (PlanNode.Route) node);
                } else if (node instanceof PlanNode.Fallback) {
                    FrameEntrant.fallback(this, frame, (PlanNode.Fallback) node);
                } else if (node instanceof PlanNode.Parallel) {
                    PlanNode.Parallel parallel = (PlanNode.Parallel) node;
                    Outcome<?> outcome;
                    try {
                        outcome = new ParallelRunner(flowId, flowVersion, state.executionId,
                                cancellation, observer, executor).run(parallel, frame.entry, deadline());
                    } catch (CancellationException cancelled) {
                        cancel();
                        break;
                    }
                    finish(outcome);
                } else if (node instanceof PlanNode.Await) {
                    MachineResult suspension = FrameEntrant.await(this, state, frame, (PlanNode.Await) node);
                    if (suspension != null) return suspension;
                } else if (node instanceof PlanNode.Control) {
                    MachineResult waiting = ControlExecutor.enter(this, frame, (PlanNode.Control) node);
                    if (waiting != null) return waiting;
                } else if (node instanceof PlanNode.Complete) {
                    PlanNode.Complete complete = (PlanNode.Complete) node;
                    Outcome<?> outcome = complete.identity()
                            ? Outcome.accepted(frame.entry) : complete.outcome();
                    finish(outcome);
                } else {
                    throw new IllegalStateException("Unknown plan node: " + node.getClass());
                }
            }
            return MachineResult.from(state, wakeAt());
        } catch (CancellationException cancelled) {
            if (!cancellation.isCancelled()) throw cancelled;
            cancel();
            return MachineResult.from(state, null);
        } finally {
            cancellation.detach(thread);
            // 仅当取消在本 flow 内部触发了中断且进入时并非已中断时，才在退出时清除取消残留的中断标记；保留所有外部中断
            if (!interrupted && cancellation.isCancelled()) Thread.interrupted();
        }
    }

    /**
     * 在 Retry/PersistentPolicy 的退避唤醒点阻塞等待。
     * 按 {@code frame.wake} 与 deadline 的较小者睡眠。
     */
    MachineResult awaitWake(RuntimeFrame frame) {
        if (!Instant.now().isBefore(frame.wake)) {
            frame.wake = null;
            return null;
        }
        while (Instant.now().isBefore(frame.wake)) {
            if (cancellation.isCancelled()) {
                cancel();
                return MachineResult.from(state, null);
            }
            Instant until = frame.wake;
            Instant deadline = deadline();
            if (deadline != null && deadline.isBefore(until)) until = deadline;
            Duration remaining = Duration.between(Instant.now(), until);
            if (remaining.isNegative() || remaining.isZero()) break;
            try {
                Thread.sleep(remaining.toMillis(), remaining.getNano() % 1000000);
            } catch (InterruptedException interrupted) {
                if (cancellation.isCancelled()) {
                    Thread.interrupted();
                    cancel();
                    return MachineResult.from(state, null);
                }
                Thread.currentThread().interrupt();
                finish(Outcome.failed(Failure.of("WAIT_INTERRUPTED",
                        "Policy wait was interrupted")));
                return null;
            }
        }
        if (expiredDeadline()) {
            timeoutNearestScope();
        }
        frame.wake = null;
        return null;
    }

    /**
     * 完成一个子帧的 Outcome 并沿帧栈向上归约：弹出已完成帧，
     * 调用 {@link FrameReducer} 把 Outcome 交给父节点；父节点返回 null 表示继续等待子帧，
     * 返回非 null 表示父节点本身完成并继续向上传播，直至栈空则落定终态 Outcome。
     */
    void finish(Outcome<?> outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (cancellationWins()) return;
        RuntimeFrame completedFrame = state.frames.get(state.frames.size() - 1);
        events.nodeCompleted(completedFrame, outcome);
        state.frames.remove(state.frames.size() - 1);
        while (!state.frames.isEmpty()) {
            RuntimeFrame parent = state.frames.get(state.frames.size() - 1);
            Outcome<?> completed = consume(parent, outcome);
            if (cancellationWins()) return;
            if (completed == null) return;
            events.nodeCompleted(parent, completed);
            state.frames.remove(state.frames.size() - 1);
            outcome = completed;
        }
        if (cancellationWins()) return;
        state.outcome = outcome;
        state.awaitingPoint = null;
        state.pendingSignal = null;
        state.lifecycle = MachineState.Lifecycle.COMPLETED;
    }

    private Outcome<?> consume(RuntimeFrame frame, Outcome<?> child) {
        return FrameReducer.consume(this, frame, child);
    }

    /**
     * 找到栈中最内层已超时的 TIMEOUT Control 帧，弹出其内部所有子帧，
     * 并以 TIMEOUT 失败完成该作用域。若没有已到期的 TIMEOUT 帧则不做任何事。
     */
    private void timeoutNearestScope() {
        int timeoutIndex = -1;
        for (int index = state.frames.size() - 1; index >= 0; index--) {
            RuntimeFrame frame = state.frames.get(index);
            if (frame.node instanceof PlanNode.Control) {
                PlanNode.Control control = (PlanNode.Control) frame.node;
                if (control.kind() == PlanNode.Control.Kind.TIMEOUT
                        && frame.deadline != null && !Instant.now().isBefore(frame.deadline)) {
                    timeoutIndex = index;
                    break;
                }
            }
        }
        if (timeoutIndex < 0) return;
        while (state.frames.size() > timeoutIndex + 1) {
            state.frames.remove(state.frames.size() - 1);
        }
        finish(Outcome.failed(Failure.of("TIMEOUT", "Flow scope deadline elapsed")));
    }

    private boolean expiredDeadline() {
        Instant deadline = deadline();
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    /** 计算当前帧栈中最紧迫的 deadline（TIMEOUT 作用域边界），无则返回 null。 */
    Instant deadline() {
        Instant result = null;
        for (RuntimeFrame frame : state.frames) {
            if (frame.deadline != null && (result == null || frame.deadline.isBefore(result)))
                result = frame.deadline;
        }
        return result;
    }

    /** 非 ACTIVE 态返回 null；否则返回帧栈中最早的 wake 或 deadline，用于唤醒时机。 */
    private Instant wakeAt() {
        if (state.lifecycle != MachineState.Lifecycle.ACTIVE) return null;
        Instant result = null;
        for (RuntimeFrame frame : state.frames) {
            if (frame.wake != null && (result == null || frame.wake.isBefore(result)))
                result = frame.wake;
            if (frame.deadline != null
                    && (result == null || frame.deadline.isBefore(result)))
                result = frame.deadline;
        }
        return result;
    }

    void push(PlanNode node, Object input) {
        state.frames.add(new RuntimeFrame(node,
                Objects.requireNonNull(input, "node input must not be null")));
    }

    void cancel() {
        cancellationCoordinator.cancel();
    }

    private boolean cancellationWins() {
        return cancellationCoordinator.cancellationWins();
    }

    PolicyContext context(final RuntimeFrame frame, final PlanNode.Control control,
                          final Cancellation callbackCancellation) {
        return new PolicyContext() {
            @Override public Metadata metadata() {
                return new Metadata(flowId, flowVersion, state.executionId,
                        control.descriptor().path(), control.descriptor().label());
            }

            @Override public int attempt() {
                return frame.attempt;
            }

            @Override public Cancellation.Signal cancellation() {
                return callbackCancellation.signal();
            }
        };
    }

    Outcome<?> policyFailure(Throwable error) {
        String message = error.getMessage();
        return Outcome.failed(Failure.of("POLICY_EXCEPTION", error.getClass().getName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message)));
    }

    static Outcome<?> timeoutFailure() {
        return Outcome.failed(Failure.of("TIMEOUT", "Flow scope deadline elapsed"));
    }

    void waitingEvent(PlanNode.Control control, RuntimeFrame frame) {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("attempt", Integer.toString(frame.attempt));
        attrs.put("wake", frame.wake.toString());
        event(FlowObserver.Type.POLICY_WAITING, control.descriptor(), attrs);
    }

    void event(FlowObserver.Type type, NodeDescriptor descriptor,
               Map<String, String> attributes) {
        events.event(type, descriptor, attributes);
    }

    boolean active() {
        return state.lifecycle == MachineState.Lifecycle.ACTIVE;
    }

    MachineResult result() {
        return MachineResult.from(state, wakeAt());
    }

    CallbackRunner callbacks() {
        return callbacks;
    }

    boolean cancelled() {
        return cancellation.isCancelled();
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
