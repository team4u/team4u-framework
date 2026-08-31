package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Cancellation;
import com.team4u.framework.flow.Completion;
import com.team4u.framework.flow.Failure;
import com.team4u.framework.flow.FlowObserver;
import com.team4u.framework.flow.Gate;
import com.team4u.framework.flow.JoinStrategy;
import com.team4u.framework.flow.Metadata;
import com.team4u.framework.flow.NodeDescriptor;
import com.team4u.framework.flow.Operation;
import com.team4u.framework.flow.OperationContext;
import com.team4u.framework.flow.Outcome;
import com.team4u.framework.flow.ParallelResults;
import com.team4u.framework.flow.Policy;
import com.team4u.framework.flow.PolicyContext;
import com.team4u.framework.flow.PersistentPolicy;
import com.team4u.framework.flow.Reason;
import com.team4u.framework.flow.Recovery;
import com.team4u.framework.flow.Resumed;
import com.team4u.framework.flow.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单个帧栈的同步推进内核，执行语义与 Core SerialMachine 严格一致：
 * sequence 仅 Accepted 推进；route 两阶段选择；fallback 按触发条件换分支；
 * control 维护 attempt/deadline/wake；parallel 顺序驱动 wait-all 后显式 join。
 *
 * <p>每个稳定边界通过 {@link Checkpoints} 提交 CAS 快照。提交总是发生在
 * "压入下一子帧之后"，因此已提交快照的栈顶永远是尚未（在本 revision 内）执行的节点，
 * 崩溃恢复从栈顶重放即可获得 at-least-once 语义。</p>
 *
 * <p>branchMode 用于 Parallel 分支：不提交检查点（仅分支完成由父帧记录），
 * 退避等待在线程内睡眠完成。</p>
 *
 * <p><b>并行分支串行驱动（合同允许的简化）</b>：Parallel 的分支按声明顺序逐个
 * 完整驱动（前序分支完成后才开始后序），不做并发执行。顺序驱动保证每次检查点
 * 提交时快照状态自洽、崩溃恢复只需重放首个空槽位之后的分支。需要真实并发执行
 * 时请使用 Core 的 Local 执行器。</p>
 */
final class DurableMachine {
    private final DurablePlanCompiler.Definition definition;
    private final String flowId;
    private final int flowVersion;
    private final DurableState.MachineState state;
    private final Checkpoints checkpoints;
    private final FlowObserver observer;
    private final ExecutorService executor;
    private final boolean branchMode;
    private boolean parked;

    DurableMachine(DurablePlanCompiler.Definition definition, String flowId, int flowVersion,
                   DurableState.MachineState state, Checkpoints checkpoints,
                   FlowObserver observer, ExecutorService executor, boolean branchMode) {
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.flowId = text(flowId, "flowId");
        this.flowVersion = flowVersion;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.executor = executor;
        this.branchMode = branchMode;
    }

    /** 同步推进帧栈直至完成、挂起或退避等待（parked，不占线程）。 */
    DurableState.MachineResult drive() {
        while (state.lifecycle == DurableLifecycle.ACTIVE && !parked) {
            checkTimeout();
            if (state.lifecycle != DurableLifecycle.ACTIVE || parked) {
                break;
            }
            DurableState.RuntimeFrame frame = state.frames.get(state.frames.size() - 1);
            nodeStarted(frame);
            DurablePlanNode node = frame.node;
            if (node instanceof DurablePlanNode.Invoke) {
                invoke(frame, (DurablePlanNode.Invoke) node);
            } else if (node instanceof DurablePlanNode.Sequence) {
                enterSequence(frame, (DurablePlanNode.Sequence) node);
            } else if (node instanceof DurablePlanNode.Route) {
                enterRoute(frame, (DurablePlanNode.Route) node);
            } else if (node instanceof DurablePlanNode.Fallback) {
                enterFallback(frame, (DurablePlanNode.Fallback) node);
            } else if (node instanceof DurablePlanNode.Parallel) {
                runParallel(frame, (DurablePlanNode.Parallel) node);
            } else if (node instanceof DurablePlanNode.Await) {
                enterAwait(frame, (DurablePlanNode.Await) node);
            } else if (node instanceof DurablePlanNode.Control) {
                enterControl(frame, (DurablePlanNode.Control) node);
            } else if (node instanceof DurablePlanNode.Complete) {
                complete(frame, (DurablePlanNode.Complete) node);
            } else {
                throw new IllegalStateException("Unknown node: " + node.getClass().getName());
            }
        }
        return result();
    }

    // ------------------------------------------------------------------
    // Invoke / Complete
    // ------------------------------------------------------------------

    private void invoke(DurableState.RuntimeFrame frame, DurablePlanNode.Invoke node) {
        long started = System.nanoTime();
        Outcome<?> outcome = invokeOperation(node, frame.entry, deadline());
        invokeCompleted(node, outcome, System.nanoTime() - started);
        finish(toMachineOutcome(node, outcome),
                CheckpointReasons.invoke(node.descriptor().path()));
    }

    /**
     * Invoke 完成事件：与 Core InvocationRunner 对齐——属性含 outcome kind、
     * durationNanos 与非 Accepted 时的诊断 code。Invoke 帧不经 finish 的
     * nodeCompleted 路径（其余帧无 duration 语义）。
     */
    private void invokeCompleted(DurablePlanNode.Invoke node, Outcome<?> outcome,
                                 long durationNanos) {
        LinkedHashMap<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("outcome", outcome.kind().name());
        attrs.put("durationNanos", Long.toString(durationNanos));
        String code = diagnosticCode(outcome);
        if (!code.isEmpty()) {
            attrs.put("code", code);
        }
        event(FlowObserver.Type.NODE_COMPLETED, node.descriptor(),
                Collections.unmodifiableMap(attrs));
    }

    @SuppressWarnings("unchecked")
    private Outcome<?> invokeOperation(final DurablePlanNode.Invoke node, final Object entry,
                                       Instant deadline) {
        if (deadline != null) {
            // deadline 检查与 executor 无关：无 executor 的同步降级同样不得越过已到期的截止
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                // 剩余时间已耗尽：直接产生 TIMEOUT 失败
                return timeoutFailure();
            }
            if (executor != null) {
                return timedInvoke(node, entry, remaining);
            }
        }
        // 无 executor 时同步降级执行（文档化行为）：deadline 由循环顶部的协作检查点保证
        return executeOperation(node, entry);
    }

    @SuppressWarnings("unchecked")
    private Outcome<?> timedInvoke(final DurablePlanNode.Invoke node, final Object entry,
                                   Duration remaining) {
        final FutureTask<Outcome<?>> task = new FutureTask<Outcome<?>>(
                new Callable<Outcome<?>>() {
                    @Override public Outcome<?> call() {
                        return executeOperation(node, entry);
                    }
                });
        try {
            executor.execute(task);
            task.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
            return task.get();
        } catch (TimeoutException timeout) {
            task.cancel(true);
            return timeoutFailure();
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return failed("OPERATION_INTERRUPTED", "Operation thread was interrupted");
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            return failed("OPERATION_EXCEPTION", describe(cause));
        } catch (RuntimeException rejected) {
            task.cancel(true);
            return failed("EXECUTOR_REJECTED",
                    rejected.getMessage() == null
                            ? "Operation execution rejected" : rejected.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Outcome<?> executeOperation(DurablePlanNode.Invoke node, Object entry) {
        try {
            Object input = Objects.requireNonNull(node.project().apply(entry),
                    "projected input must not be null");
            Outcome<Object> outcome = Objects.requireNonNull(
                    ((Operation<Object, Object>) node.binding().instance())
                            .execute(new OperationContextImpl(node.descriptor()), input),
                    "operation outcome must not be null");
            if (outcome instanceof Outcome.Accepted) {
                Object merged = Objects.requireNonNull(
                        node.merge().apply(entry, ((Outcome.Accepted<Object>) outcome).value()),
                        "merged output must not be null");
                return Outcome.accepted(merged);
            }
            return outcome;
        } catch (CancellationException cancelled) {
            return failed("OPERATION_CANCELLED", "Operation was cancelled");
        } catch (Exception error) {
            return failed("OPERATION_EXCEPTION", describe(error));
        }
    }

    private DurableState.MachineOutcome toMachineOutcome(DurablePlanNode.Invoke node,
                                                         Outcome<?> outcome) {
        Object value = acceptedValue(outcome);
        if (value == null) {
            return DurableState.MachineOutcome.of(outcome);
        }
        return DurableState.MachineOutcome.accepted(value, DurableState.SlotRole.user(
                DurablePlanCompiler.nodeRole(node.descriptor().path())));
    }

    private void complete(DurableState.RuntimeFrame frame, DurablePlanNode.Complete node) {
        DurableState.MachineOutcome outcome;
        if (node.identity()) {
            outcome = DurableState.MachineOutcome.accepted(frame.entry, frame.entryRole);
        } else {
            Outcome<?> fixed = node.outcome();
            Object value = acceptedValue(fixed);
            if (value == null) {
                outcome = DurableState.MachineOutcome.of(fixed);
            } else {
                outcome = DurableState.MachineOutcome.accepted(value,
                        DurableState.SlotRole.user(
                                DurablePlanCompiler.nodeRole(node.descriptor().path())));
            }
        }
        finish(outcome, CheckpointReasons.complete(node.descriptor().path()));
    }

    // ------------------------------------------------------------------
    // Sequence
    // ------------------------------------------------------------------

    private void enterSequence(DurableState.RuntimeFrame frame, DurablePlanNode.Sequence node) {
        if (frame.phase != 0) {
            throw new IllegalStateException(
                    "Sequence child frame missing at " + node.descriptor().path());
        }
        if (node.children().isEmpty()) {
            finish(DurableState.MachineOutcome.accepted(frame.entry, frame.entryRole),
                    CheckpointReasons.boundary("sequence",
                            node.descriptor().path()));
            return;
        }
        frame.phase = 1;
        frame.index = 0;
        push(node.children().get(0), frame.current, frame.currentRole);
    }

    /**
     * @return null 表示已压入下一子帧继续等待；非 null 表示本帧完成。
     */
    private DurableState.MachineOutcome reduceSequence(DurableState.RuntimeFrame frame,
                                                       DurablePlanNode.Sequence node,
                                                       DurableState.MachineOutcome child) {
        if (!(child.outcome() instanceof Outcome.Accepted)) {
            return child;
        }
        Outcome.Accepted<?> accepted = (Outcome.Accepted<?>) child.outcome();
        frame.current = accepted.value();
        frame.currentRole = child.acceptedRole();
        frame.index++;
        if (frame.index >= node.children().size()) {
            return DurableState.MachineOutcome.accepted(frame.current, frame.currentRole);
        }
        push(node.children().get(frame.index), frame.current, frame.currentRole);
        return null;
    }

    // ------------------------------------------------------------------
    // Route
    // ------------------------------------------------------------------

    private void enterRoute(DurableState.RuntimeFrame frame, DurablePlanNode.Route node) {
        if (frame.phase != 0) {
            throw new IllegalStateException(
                    "Route child frame missing at " + node.descriptor().path());
        }
        frame.phase = 1;
        push(node.selector(), frame.entry, frame.entryRole);
    }

    private DurableState.MachineOutcome reduceRoute(DurableState.RuntimeFrame frame,
                                                    DurablePlanNode.Route node,
                                                    DurableState.MachineOutcome child) {
        if (frame.phase == 1) {
            if (!(child.outcome() instanceof Outcome.Accepted)) {
                return child;
            }
            Object key = ((Outcome.Accepted<?>) child.outcome()).value();
            List<DurablePlanNode.Route.RouteCase> cases = node.cases();
            int selected = -1;
            for (int index = 0; index < cases.size(); index++) {
                if (cases.get(index).key().equals(key)) {
                    selected = index;
                    break;
                }
            }
            frame.phase = 2;
            frame.index = selected;
            if (selected >= 0) {
                frame.selected = "case:" + selected;
                push(cases.get(selected).branch(), frame.entry, frame.entryRole);
                event(FlowObserver.Type.ROUTE_SELECTED, node.descriptor(),
                        singleton("branch", frame.selected));
                return null;
            }
            if (node.otherwise() != null) {
                frame.selected = "otherwise";
                push(node.otherwise(), frame.entry, frame.entryRole);
                event(FlowObserver.Type.ROUTE_SELECTED, node.descriptor(),
                        singleton("branch", frame.selected));
                return null;
            }
            return DurableState.MachineOutcome.of(Outcome.skipped(
                    Reason.of("NO_ROUTE", "No route case matched the selector")));
        }
        if (frame.phase == 2) {
            return child;
        }
        throw new IllegalStateException("Invalid Route phase at " + node.descriptor().path());
    }

    // ------------------------------------------------------------------
    // Fallback
    // ------------------------------------------------------------------

    private void enterFallback(DurableState.RuntimeFrame frame, DurablePlanNode.Fallback node) {
        if (frame.phase != 0) {
            throw new IllegalStateException(
                    "Fallback child frame missing at " + node.descriptor().path());
        }
        frame.phase = 1;
        frame.index = 0;
        push(node.branches().get(0), frame.entry, frame.entryRole);
    }

    private DurableState.MachineOutcome reduceFallback(DurableState.RuntimeFrame frame,
                                                       DurablePlanNode.Fallback node,
                                                       DurableState.MachineOutcome child) {
        boolean skippedTrigger = node.trigger()
                == com.team4u.framework.flow.FallbackTrigger.SKIPPED;
        boolean triggered = skippedTrigger
                ? child.outcome() instanceof Outcome.Skipped
                : child.outcome() instanceof Outcome.Failed;
        if (!triggered || frame.index + 1 >= node.branches().size()) {
            return child;
        }
        frame.index++;
        Object input = frame.entry;
        DurableState.SlotRole inputRole = frame.entryRole;
        if (!skippedTrigger) {
            Failure failure = ((Outcome.Failed<?>) child.outcome()).failure();
            input = new Recovery<Object>(frame.entry, failure);
            inputRole = new DurableState.SlotRole.Recovery(frame.entryRole);
        }
        frame.selected = "branch:" + frame.index;
        push(node.branches().get(frame.index), input, inputRole);
        event(FlowObserver.Type.FALLBACK_SELECTED, node.descriptor(),
                singleton("branch", frame.selected));
        return null;
    }

    // ------------------------------------------------------------------
    // Await
    // ------------------------------------------------------------------

    private void enterAwait(DurableState.RuntimeFrame frame, DurablePlanNode.Await node) {
        if (state.pendingSignal != null) {
            if (!node.point().name().equals(state.awaitingPoint)) {
                throw new IllegalStateException(
                        "Pending resume point does not match Await frame");
            }
            Object signal = state.pendingSignal;
            state.pendingSignal = null;
            state.awaitingPoint = null;
            DurableState.SlotRole resumedRole = new DurableState.SlotRole.Resumed(
                    frame.entryRole, node.point().name());
            finish(DurableState.MachineOutcome.accepted(
                    new Resumed<Object, Object>(frame.entry, signal), resumedRole),
                    CheckpointReasons.await(node.point().name()));
            return;
        }
        state.lifecycle = DurableLifecycle.SUSPENDED;
        state.awaitingPoint = node.point().name();
        checkpoints.commit(CheckpointReasons.await(node.point().name()));
        event(FlowObserver.Type.FLOW_SUSPENDED, node.descriptor(),
                singleton("resumePoint", node.point().name()));
    }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------

    private void enterControl(DurableState.RuntimeFrame frame, DurablePlanNode.Control node) {
        switch (node.kind()) {
            case TIMEOUT:
                enterTimeout(frame, node);
                return;
            case RETRY:
                enterRetry(frame, node);
                return;
            case POLICY:
                enterPolicy(frame, node);
                return;
            case PERSISTENT_POLICY:
                enterPersistent(frame, node);
                return;
            default:
                throw new IllegalStateException("Unknown control kind: " + node.kind());
        }
    }

    private void enterTimeout(DurableState.RuntimeFrame frame, DurablePlanNode.Control node) {
        if (frame.phase != 0) {
            throw new IllegalStateException(
                    "Timeout body frame missing at " + node.descriptor().path());
        }
        frame.deadline = Instant.now().plus((Duration) node.configuration());
        frame.phase = 1;
        push(node.body(), frame.entry, frame.entryRole);
    }

    private void enterRetry(DurableState.RuntimeFrame frame, DurablePlanNode.Control node) {
        if (frame.phase == 0 || frame.phase == 2) {
            if (frame.phase == 2 && frame.wake != null
                    && Instant.now().isBefore(frame.wake)) {
                waitOrPark(frame, node);
                return;
            }
            frame.wake = null;
            frame.phase = 1;
            push(node.body(), frame.entry, frame.entryRole);
            return;
        }
        throw new IllegalStateException(
                "Retry body frame missing at " + node.descriptor().path());
    }

    @SuppressWarnings("unchecked")
    private void enterPolicy(DurableState.RuntimeFrame frame, DurablePlanNode.Control node) {
        if (frame.phase != 0) {
            throw new IllegalStateException(
                    "Policy body frame missing at " + node.descriptor().path());
        }
        Policy<Object> policy = (Policy<Object>) node.binding().get().instance();
        event(FlowObserver.Type.POLICY_BEFORE, node.descriptor(),
                singleton("attempt", Integer.toString(frame.attempt)));
        Object key;
        Gate gate;
        try {
            key = Objects.requireNonNull(node.keyProjection().apply(frame.entry),
                    "policy key must not be null");
            gate = Objects.requireNonNull(
                    policy.before(policyContext(node, frame), key),
                    "policy gate must not be null");
        } catch (Exception error) {
            finish(DurableState.MachineOutcome.of(policyFailure(error)),
                    CheckpointReasons.control(node.descriptor().path()));
            return;
        }
        frame.key = key;
        if (gate instanceof Gate.Proceed) {
            frame.phase = 1;
            push(node.body(), frame.entry, frame.entryRole);
            return;
        }
        if (gate instanceof Gate.Reject) {
            finish(DurableState.MachineOutcome.of(
                            Outcome.rejected(((Gate.Reject) gate).reason())),
                    CheckpointReasons.control(node.descriptor().path()));
            return;
        }
        if (gate instanceof Gate.Fail) {
            finish(DurableState.MachineOutcome.of(
                            Outcome.failed(((Gate.Fail) gate).failure())),
                    CheckpointReasons.control(node.descriptor().path()));
            return;
        }
        throw new IllegalStateException(
                "Unknown Gate decision: " + gate.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private DurableState.MachineOutcome reducePolicy(DurableState.RuntimeFrame frame,
                                                     DurablePlanNode.Control node,
                                                     DurableState.MachineOutcome child) {
        Policy<Object> policy = (Policy<Object>) node.binding().get().instance();
        try {
            policy.after(policyContext(node, frame), frame.key,
                    CompletionAdapter.from(child.outcome()));
        } catch (Exception error) {
            event(FlowObserver.Type.POLICY_AFTER, node.descriptor(),
                    policyAfterAttrs(frame, child));
            return DurableState.MachineOutcome.of(policyFailure(error));
        }
        event(FlowObserver.Type.POLICY_AFTER, node.descriptor(),
                policyAfterAttrs(frame, child));
        return child;
    }

    @SuppressWarnings("unchecked")
    private void enterPersistent(DurableState.RuntimeFrame frame,
                                 DurablePlanNode.Control node) {
        PersistentPolicy<Object, Object> policy =
                (PersistentPolicy<Object, Object>) node.binding().get().instance();
        if (frame.phase == 2 || frame.phase == 3) {
            if (frame.wake != null && Instant.now().isBefore(frame.wake)) {
                waitOrPark(frame, node);
                return;
            }
            frame.wake = null;
            frame.phase = 0;
        }
        if (frame.phase != 0) {
            throw new IllegalStateException(
                    "PersistentPolicy body frame missing at " + node.descriptor().path());
        }
        event(FlowObserver.Type.POLICY_BEFORE, node.descriptor(),
                singleton("attempt", Integer.toString(frame.attempt)));
        Object key;
        Object currentState;
        PersistentPolicy.Before<Object> decision;
        try {
            key = frame.key == null
                    ? Objects.requireNonNull(node.keyProjection().apply(frame.entry),
                    "policy key must not be null")
                    : frame.key;
            currentState = frame.policyState == null
                    ? Objects.requireNonNull(policy.initialState(key),
                    "policy initial state must not be null")
                    : frame.policyState;
            decision = Objects.requireNonNull(
                    policy.before(policyContext(node, frame), key, currentState),
                    "policy before decision must not be null");
        } catch (Exception error) {
            finish(DurableState.MachineOutcome.of(policyFailure(error)),
                    CheckpointReasons.control(node.descriptor().path()));
            return;
        }
        frame.key = key;
        frame.policyState = currentState;
        if (decision instanceof PersistentPolicy.Proceed) {
            PersistentPolicy.Proceed<Object> proceed =
                    (PersistentPolicy.Proceed<Object>) decision;
            frame.policyState = proceed.state();
            frame.phase = 1;
            push(node.body(), frame.entry, frame.entryRole);
            // 与 Core ControlExecutor.persistent 一致：决策后立即提交，
            // 保证 key/policyState 在 body 执行前已持久化（崩溃后不再重调 initialState）。
            checkpoints.commit(CheckpointReasons.control(node.descriptor().path()));
            return;
        }
        if (decision instanceof PersistentPolicy.WaitUntil) {
            PersistentPolicy.WaitUntil<Object> wait =
                    (PersistentPolicy.WaitUntil<Object>) decision;
            frame.policyState = wait.state();
            frame.wake = wait.instant();
            frame.phase = 2;
            checkpoints.commit(CheckpointReasons.control(node.descriptor().path()));
            waitingEvent(node, frame);
            return;
        }
        if (decision instanceof PersistentPolicy.Reject) {
            PersistentPolicy.Reject<Object> reject =
                    (PersistentPolicy.Reject<Object>) decision;
            frame.policyState = reject.state();
            finish(DurableState.MachineOutcome.of(Outcome.rejected(reject.reason())),
                    CheckpointReasons.control(node.descriptor().path()));
            return;
        }
        if (decision instanceof PersistentPolicy.Fail) {
            PersistentPolicy.Fail<Object> fail = (PersistentPolicy.Fail<Object>) decision;
            frame.policyState = fail.state();
            finish(DurableState.MachineOutcome.of(Outcome.failed(fail.failure())),
                    CheckpointReasons.control(node.descriptor().path()));
            return;
        }
        throw new IllegalStateException("Unknown PersistentPolicy.Before decision: "
                + decision.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private DurableState.MachineOutcome reducePersistent(DurableState.RuntimeFrame frame,
                                                         DurablePlanNode.Control node,
                                                         DurableState.MachineOutcome child) {
        PersistentPolicy<Object, Object> policy =
                (PersistentPolicy<Object, Object>) node.binding().get().instance();
        PersistentPolicy.After<Object> decision;
        try {
            decision = Objects.requireNonNull(
                    policy.after(policyContext(node, frame), frame.key,
                            frame.policyState, CompletionAdapter.from(child.outcome())),
                    "policy after decision must not be null");
        } catch (Exception error) {
            event(FlowObserver.Type.POLICY_AFTER, node.descriptor(),
                    policyAfterAttrs(frame, child));
            return DurableState.MachineOutcome.of(policyFailure(error));
        }
        event(FlowObserver.Type.POLICY_AFTER, node.descriptor(),
                policyAfterAttrs(frame, child));
        if (decision instanceof PersistentPolicy.Return) {
            frame.policyState = ((PersistentPolicy.Return<Object>) decision).state();
            return child;
        }
        if (decision instanceof PersistentPolicy.RetryAt) {
            PersistentPolicy.RetryAt<Object> retryAt =
                    (PersistentPolicy.RetryAt<Object>) decision;
            frame.policyState = retryAt.state();
            frame.wake = retryAt.instant();
            frame.attempt++;
            frame.phase = 3;
            checkpoints.commit(CheckpointReasons.control(node.descriptor().path()));
            waitingEvent(node, frame);
            return null;
        }
        throw new IllegalStateException("Unknown PersistentPolicy.After decision: "
                + decision.getClass().getName());
    }

    private DurableState.MachineOutcome reduceControl(DurableState.RuntimeFrame frame,
                                                      DurablePlanNode.Control node,
                                                      DurableState.MachineOutcome child) {
        switch (node.kind()) {
            case TIMEOUT: {
                boolean timedOut = frame.deadline != null
                        && !Instant.now().isBefore(frame.deadline);
                frame.deadline = null;
                return timedOut
                        ? DurableState.MachineOutcome.of(timeoutFailure())
                        : child;
            }
            case RETRY: {
                Retry retry = (Retry) node.configuration();
                if (child.outcome() instanceof Outcome.Failed
                        && frame.attempt < retry.maxAttempts()) {
                    frame.attempt++;
                    frame.wake = Instant.now().plus(retry.backoff());
                    frame.phase = 2;
                    checkpoints.commit(CheckpointReasons.control(node.descriptor().path()));
                    waitingEvent(node, frame);
                    return null;
                }
                return child;
            }
            case POLICY:
                return reducePolicy(frame, node, child);
            case PERSISTENT_POLICY:
                return reducePersistent(frame, node, child);
            default:
                throw new IllegalStateException("Unknown control kind: " + node.kind());
        }
    }

    /** 退避等待：分支内联睡眠；主栈直接 park 并由快照中的绝对 wake 驱动恢复。 */
    private void waitOrPark(DurableState.RuntimeFrame frame, DurablePlanNode.Control node) {
        if (!branchMode) {
            parked = true;
            return;
        }
        while (Instant.now().isBefore(frame.wake)) {
            try {
                Duration remaining = Duration.between(Instant.now(), frame.wake);
                long millis = Math.max(1, remaining.toMillis());
                Thread.sleep(Math.min(millis, 50));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                frame.wake = null;
                finish(DurableState.MachineOutcome.of(
                        failed("WAIT_INTERRUPTED", "Policy wait was interrupted")),
                        CheckpointReasons.control(node.descriptor().path()));
                return;
            }
        }
        frame.wake = null;
    }

    // ------------------------------------------------------------------
    // Parallel
    // ------------------------------------------------------------------

    private void runParallel(DurableState.RuntimeFrame frame, DurablePlanNode.Parallel node) {
        // 分支按声明顺序串行驱动（见类注释）：首个 null 槽位即下一个待执行分支
        if (frame.phase == 0) {
            frame.phase = 1;
            event(FlowObserver.Type.PARALLEL_STARTED, node.descriptor(),
                    singleton("branches", Integer.toString(node.branches().size())));
        }
        int next = -1;
        for (int index = 0; index < frame.branchOutcomes.size(); index++) {
            if (frame.branchOutcomes.get(index) == null) {
                next = index;
                break;
            }
        }
        if (next >= 0) {
            runBranch(frame, node, next);
            return;
        }
        joinParallel(frame, node);
    }

    private void runBranch(DurableState.RuntimeFrame frame, DurablePlanNode.Parallel node,
                           int index) {
        DurablePlanNode.Parallel.ParallelBranch branch = node.branches().get(index);
        DurableState.MachineState branchState = new DurableState.MachineState(
                branch.plan(), state.executionId, frame.entry, frame.entryRole);
        DurableMachine machine = new DurableMachine(definition, flowId, flowVersion,
                branchState, Checkpoints.INERT, observer, executor, true);
        DurableState.MachineResult result = machine.drive();
        if (result.lifecycle() != DurableLifecycle.COMPLETED) {
            throw invalid("Parallel branch ended in " + result.lifecycle()
                    + " at " + branch.token().name());
        }
        frame.branchOutcomes.set(index, result.outcome());
        checkpoints.commit(CheckpointReasons.parallelBranch(node.descriptor().path(),
                branch.token().name()));
        event(FlowObserver.Type.PARALLEL_BRANCH_COMPLETED, node.descriptor(),
                parallelBranchAttrs(branch.token().name(), result.outcome().outcome()));
    }

    private void joinParallel(DurableState.RuntimeFrame frame, DurablePlanNode.Parallel node) {
        List<com.team4u.framework.flow.Branch<?, ?>> tokens =
                new ArrayList<com.team4u.framework.flow.Branch<?, ?>>();
        List<Outcome<?>> outcomes = new ArrayList<Outcome<?>>();
        for (int index = 0; index < node.branches().size(); index++) {
            tokens.add(node.branches().get(index).token());
            outcomes.add(frame.branchOutcomes.get(index).outcome());
        }
        JoinStrategy<?> join = node.join();
        Outcome<?> joined;
        try {
            joined = Objects.requireNonNull(join.join(ParallelResults.of(tokens, outcomes)),
                    "parallel join returned null");
        } catch (Exception error) {
            joined = failed("JOIN_EXCEPTION", describe(error));
        }
        event(FlowObserver.Type.PARALLEL_JOINED, node.descriptor(),
                singleton("outcome", joined.kind().name()));
        Object value = acceptedValue(joined);
        DurableState.MachineOutcome machineJoined;
        if (value == null) {
            machineJoined = DurableState.MachineOutcome.of(joined);
        } else {
            machineJoined = DurableState.MachineOutcome.accepted(value,
                    DurableState.SlotRole.user(
                            DurablePlanCompiler.nodeRole(node.descriptor().path())));
        }
        finish(machineJoined,
                CheckpointReasons.parallelJoin(node.descriptor().path()));
    }

    // ------------------------------------------------------------------
    // Frame stack mechanics
    // ------------------------------------------------------------------

    private void push(DurablePlanNode node, Object input, DurableState.SlotRole role) {
        state.frames.add(new DurableState.RuntimeFrame(node, input, role));
    }

    /**
     * 完成栈顶帧并沿帧栈向上归约。每个稳定边界提交一次 CAS 检查点；
     * 提交发生在压入下一子帧或落定终态之后，保证快照栈顶总是可安全重放的节点。
     */
    private void finish(DurableState.MachineOutcome outcome, CheckpointReasons.Reason reason) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        DurableState.RuntimeFrame completedFrame =
                state.frames.get(state.frames.size() - 1);
        nodeCompleted(completedFrame, outcome.outcome());
        state.frames.remove(state.frames.size() - 1);
        while (!state.frames.isEmpty()) {
            DurableState.RuntimeFrame parent = state.frames.get(state.frames.size() - 1);
            DurableState.MachineOutcome completed = consume(parent, outcome);
            if (completed == null) {
                checkpoints.commit(reason);
                return;
            }
            nodeCompleted(parent, completed.outcome());
            state.frames.remove(state.frames.size() - 1);
            outcome = completed;
        }
        state.outcome = outcome;
        state.awaitingPoint = null;
        state.pendingSignal = null;
        state.lifecycle = DurableLifecycle.COMPLETED;
        checkpoints.commit(reason);
    }

    private DurableState.MachineOutcome consume(DurableState.RuntimeFrame frame,
                                                DurableState.MachineOutcome child) {
        DurablePlanNode node = frame.node;
        if (node instanceof DurablePlanNode.Sequence) {
            return reduceSequence(frame, (DurablePlanNode.Sequence) node, child);
        }
        if (node instanceof DurablePlanNode.Route) {
            return reduceRoute(frame, (DurablePlanNode.Route) node, child);
        }
        if (node instanceof DurablePlanNode.Fallback) {
            return reduceFallback(frame, (DurablePlanNode.Fallback) node, child);
        }
        if (node instanceof DurablePlanNode.Control) {
            return reduceControl(frame, (DurablePlanNode.Control) node, child);
        }
        throw new IllegalStateException(
                "Node cannot consume child outcome: " + node.getClass().getName());
    }

    /** 超时作用域：找最内层已到期的 TIMEOUT 帧并以其边界转换为 TIMEOUT 失败。 */
    private void timeoutNearestScope() {
        int timeoutIndex = -1;
        for (int index = state.frames.size() - 1; index >= 0; index--) {
            DurableState.RuntimeFrame frame = state.frames.get(index);
            if (frame.node instanceof DurablePlanNode.Control) {
                DurablePlanNode.Control control = (DurablePlanNode.Control) frame.node;
                if (control.kind() == com.team4u.framework.flow.ControlKind.TIMEOUT
                        && frame.deadline != null
                        && !Instant.now().isBefore(frame.deadline)) {
                    timeoutIndex = index;
                    break;
                }
            }
        }
        if (timeoutIndex < 0) {
            return;
        }
        DurableState.RuntimeFrame scope = state.frames.get(timeoutIndex);
        while (state.frames.size() > timeoutIndex + 1) {
            state.frames.remove(state.frames.size() - 1);
        }
        scope.deadline = null;
        finish(DurableState.MachineOutcome.of(timeoutFailure()),
                CheckpointReasons.control(scope.node.descriptor().path()));
    }

    private Instant deadline() {
        Instant result = null;
        for (DurableState.RuntimeFrame frame : state.frames) {
            if (frame.deadline != null
                    && (result == null || frame.deadline.isBefore(result))) {
                result = frame.deadline;
            }
        }
        return result;
    }

    private void checkTimeout() {
        Instant deadline = deadline();
        if (deadline != null && !Instant.now().isBefore(deadline)) {
            timeoutNearestScope();
        }
    }

    private DurableState.MachineResult result() {
        Instant wakeAt = null;
        if (state.lifecycle == DurableLifecycle.ACTIVE) {
            // 与 Core SerialMachine.wakeAt 对齐：唤醒时机取帧栈中最早的 wake 或 deadline
            for (DurableState.RuntimeFrame frame : state.frames) {
                if (frame.wake != null
                        && (wakeAt == null || frame.wake.isBefore(wakeAt))) {
                    wakeAt = frame.wake;
                }
                if (frame.deadline != null
                        && (wakeAt == null || frame.deadline.isBefore(wakeAt))) {
                    wakeAt = frame.deadline;
                }
            }
        }
        return new DurableState.MachineResult(state.lifecycle, state.outcome,
                state.awaitingPoint, wakeAt);
    }

    // ------------------------------------------------------------------
    // Observers and policy context
    // ------------------------------------------------------------------

    private void nodeStarted(DurableState.RuntimeFrame frame) {
        if (frame.observerStarted) {
            return;
        }
        frame.observerStarted = true;
        event(FlowObserver.Type.NODE_STARTED, frame.node.descriptor(),
                attributes(frame, null));
    }

    private void nodeCompleted(DurableState.RuntimeFrame frame, Outcome<?> outcome) {
        // Invoke 帧的 NODE_COMPLETED 由 invoke() 在完成时发布（含 durationNanos）
        if (frame.node instanceof DurablePlanNode.Invoke) {
            return;
        }
        event(FlowObserver.Type.NODE_COMPLETED, frame.node.descriptor(),
                attributes(frame, outcome));
    }

    private void waitingEvent(DurablePlanNode.Control node, DurableState.RuntimeFrame frame) {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("attempt", Integer.toString(frame.attempt));
        attrs.put("wake", frame.wake.toString());
        event(FlowObserver.Type.POLICY_WAITING, node.descriptor(), attrs);
    }

    private PolicyContext policyContext(final DurablePlanNode.Control node,
                                        final DurableState.RuntimeFrame frame) {
        return new PolicyContext() {
            @Override public Metadata metadata() {
                return new Metadata(flowId, flowVersion, state.executionId,
                        node.descriptor().path(), node.descriptor().label());
            }

            @Override public int attempt() {
                return frame.attempt;
            }

            @Override public Cancellation.Signal cancellation() {
                return NOT_CANCELLED;
            }
        };
    }

    private final class OperationContextImpl implements OperationContext {
        private final NodeDescriptor descriptor;

        OperationContextImpl(NodeDescriptor descriptor) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        }

        @Override public Metadata metadata() {
            return new Metadata(flowId, flowVersion, state.executionId,
                    descriptor.path(), descriptor.label());
        }

        @Override public String invocationId() {
            return flowId + ":" + flowVersion + ":" + state.executionId
                    + ":" + descriptor.path();
        }

        @Override public Cancellation.Signal cancellation() {
            return NOT_CANCELLED;
        }
    }

    private void event(FlowObserver.Type type, NodeDescriptor descriptor,
                       Map<String, String> attributes) {
        try {
            observer.onEvent(new FlowObserver.Event(type, Instant.now(),
                    new Metadata(flowId, flowVersion, state.executionId,
                            descriptor.path(), descriptor.label()),
                    descriptor, attributes));
        } catch (RuntimeException ignored) {
            // Observers cannot alter execution.
        }
    }

    private Map<String, String> attributes(DurableState.RuntimeFrame frame,
                                           Outcome<?> outcome) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<String, String>();
        if (frame.node instanceof DurablePlanNode.Sequence) {
            String scope = ((DurablePlanNode.Sequence) frame.node).scopeName().orElse(null);
            if (scope != null) {
                attributes.put("scope", scope);
            }
        }
        if (frame.node instanceof DurablePlanNode.Control) {
            attributes.put("attempt", Integer.toString(frame.attempt));
        }
        if (frame.selected != null) {
            attributes.put("branch", frame.selected);
        }
        if (outcome != null) {
            attributes.put("outcome", outcome.kind().name());
            String code = diagnosticCode(outcome);
            if (!code.isEmpty()) {
                attributes.put("code", code);
            }
        }
        return Collections.unmodifiableMap(attributes);
    }

    private Map<String, String> policyAfterAttrs(DurableState.RuntimeFrame frame,
                                                 DurableState.MachineOutcome child) {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("attempt", Integer.toString(frame.attempt));
        attrs.put("outcome", child.outcome().kind().name());
        return Collections.unmodifiableMap(attrs);
    }

    private Map<String, String> parallelBranchAttrs(String branch, Outcome<?> outcome) {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("branch", branch);
        attrs.put("outcome", outcome.kind().name());
        return Collections.unmodifiableMap(attrs);
    }

    private static String diagnosticCode(Outcome<?> outcome) {
        if (outcome instanceof Outcome.Rejected) {
            return ((Outcome.Rejected<?>) outcome).reason().code();
        }
        if (outcome instanceof Outcome.Skipped) {
            return ((Outcome.Skipped<?>) outcome).reason().code();
        }
        if (outcome instanceof Outcome.Failed) {
            return ((Outcome.Failed<?>) outcome).failure().code();
        }
        return "";
    }

    private static Outcome<?> timeoutFailure() {
        return failed("TIMEOUT", "Flow scope deadline elapsed");
    }

    private static Outcome<?> failed(String code, String message) {
        return Outcome.failed(Failure.of(code, message));
    }

    private static Outcome<?> policyFailure(Throwable error) {
        return failed("POLICY_EXCEPTION", describe(error));
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    private static Object acceptedValue(Outcome<?> outcome) {
        if (outcome instanceof Outcome.Accepted) {
            return ((Outcome.Accepted<?>) outcome).value();
        }
        return null;
    }

    private static Map<String, String> singleton(String key, String value) {
        return Collections.singletonMap(key, value);
    }

    private static DurableException invalid(String message) {
        return new DurableException(DurableException.Error.INVALID_DEFINITION, message);
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static final Cancellation.Signal NOT_CANCELLED = new Cancellation.Signal() {
        @Override public boolean isCancelled() {
            return false;
        }
    };
}
