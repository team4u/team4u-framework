package com.team4u.framework.flow.durable;


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
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.durable.engine.CheckpointReasons;
import com.team4u.framework.flow.durable.engine.Checkpoints;
import com.team4u.framework.flow.durable.engine.DurableFrameReducePolicy;
import com.team4u.framework.flow.durable.engine.DurableFrameReducePolicyRegistry;
import com.team4u.framework.flow.durable.engine.DurableNodeExecutionHandler;
import com.team4u.framework.flow.durable.engine.DurableNodeExecutionHandlerRegistry;
import com.team4u.framework.flow.durable.engine.DurablePlanCompiler;
import com.team4u.framework.flow.durable.engine.DurablePlanNode;
import com.team4u.framework.flow.durable.engine.DurableState;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.spi.ControlKind;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Metadata;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowDiagnosticCodes;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.ParallelResults;
import com.team4u.framework.flow.model.Resumed;
import com.team4u.framework.flow.spi.NodeDescriptor;

/**
 * 耐久化流程状态机执行内核（Durable Execution State Machine）。
 *
 * <p>核心架构与执行语义：
 * <ul>
 *   <li><b>严格与 Core 对齐</b>：通过 {@link DurableNodeExecutionHandlerRegistry} 与 {@link DurableFrameReducePolicyRegistry} 实现微内核策略分发，保持与 Core 一致的四态逻辑语义；</li>
 *   <li><b>检查点原子持久化（At-least-once）</b>：每个稳定边界通过 {@link Checkpoints} 提交 CAS 快照；</li>
 *   <li><b>无线程占用的 Park 机制</b>：在遇到 Retry 退避或 PersistentPolicy 调度时，主执行栈持久化快照后退出线程（Parked）；</li>
 *   <li><b>声明顺序串行驱动 Parallel</b>：Parallel 的分支按声明顺序逐个完整执行并落库，保障确定的崩溃恢复拓扑。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public final class DurableMachine {
    private final DurablePlanCompiler.Definition definition;
    private final String flowId;
    private final int flowVersion;
    private final DurableState.MachineState state;
    private final Checkpoints checkpoints;
    private final FlowObserver observer;
    private final ExecutorService executor;
    private final boolean branchMode;
    private boolean parked;

    public DurableMachine(DurablePlanCompiler.Definition definition, String flowId, int flowVersion,
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

    /**
     * 同步推进帧栈，直至完成（COMPLETED）、挂起（SUSPENDED）或进入退避等待（Parked）。
     *
     * @return 状态机推进结果 {@link DurableState.MachineResult}
     */
    @SuppressWarnings("unchecked")
    public DurableState.MachineResult drive() {
        while (state.lifecycle == DurableLifecycle.ACTIVE && !parked) {
            checkTimeout();
            if (state.lifecycle != DurableLifecycle.ACTIVE || parked) {
                break;
            }
            DurableState.RuntimeFrame frame = state.frames.get(state.frames.size() - 1);
            nodeStarted(frame);
            DurablePlanNode node = frame.node;

            DurableNodeExecutionHandler<DurablePlanNode> handler = (DurableNodeExecutionHandler<DurablePlanNode>) DurableNodeExecutionHandlerRegistry.global()
                    .get(node.getClass())
                    .orElseThrow(() -> new IllegalStateException("Unknown node: " + node.getClass().getName()));
            handler.execute(node, frame, this);
        }
        return result();
    }

    // ------------------------------------------------------------------
    // Invoke / Complete 执行支持
    // ------------------------------------------------------------------
    public void invoke(DurableState.RuntimeFrame frame, DurablePlanNode.Invoke node) {
        long started = System.nanoTime();
        Outcome<?> outcome = invokeOperation(node, frame.entry, deadline());
        invokeCompleted(node, outcome, System.nanoTime() - started);
        finish(toMachineOutcome(node, outcome),
                CheckpointReasons.invoke(node.descriptor().path()));
    }

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
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                return timeoutFailure();
            }
            if (executor != null) {
                return timedInvoke(node, entry, remaining);
            }
        }
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
            return failed(FlowDiagnosticCodes.OPERATION_INTERRUPTED, "Operation thread was interrupted");
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            return failed(FlowDiagnosticCodes.OPERATION_EXCEPTION, describe(cause));
        } catch (RuntimeException rejected) {
            task.cancel(true);
            return failed(FlowDiagnosticCodes.EXECUTOR_REJECTED,
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
            return failed(FlowDiagnosticCodes.OPERATION_CANCELLED, "Operation was cancelled");
        } catch (Exception error) {
            return failed(FlowDiagnosticCodes.OPERATION_EXCEPTION, describe(error));
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

    public void complete(DurableState.RuntimeFrame frame, DurablePlanNode.Complete node) {
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
    // Sequence / Route / Fallback / Await 调度支持
    // ------------------------------------------------------------------
    public void enterSequence(DurableState.RuntimeFrame frame, DurablePlanNode.Sequence node) {
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
    public void enterRoute(DurableState.RuntimeFrame frame, DurablePlanNode.Route node) {
        if (frame.phase != 0) {
            throw new IllegalStateException(
                    "Route child frame missing at " + node.descriptor().path());
        }
        frame.phase = 1;
        push(node.selector(), frame.entry, frame.entryRole);
    }
    public void enterFallback(DurableState.RuntimeFrame frame, DurablePlanNode.Fallback node) {
        if (frame.phase != 0) {
            throw new IllegalStateException(
                    "Fallback child frame missing at " + node.descriptor().path());
        }
        frame.phase = 1;
        frame.index = 0;
        push(node.branches().get(0), frame.entry, frame.entryRole);
    }
    public void enterAwait(DurableState.RuntimeFrame frame, DurablePlanNode.Await node) {
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
    // Parallel 调度支持
    // ------------------------------------------------------------------
    public void runParallel(DurableState.RuntimeFrame frame, DurablePlanNode.Parallel node) {
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
        List<com.team4u.framework.flow.api.Branch<?, ?>> tokens =
                new ArrayList<com.team4u.framework.flow.api.Branch<?, ?>>();
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
            joined = failed(FlowDiagnosticCodes.JOIN_EXCEPTION, describe(error));
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
    // Frame 堆栈与归约协调
    // ------------------------------------------------------------------
    public void push(DurablePlanNode node, Object input, DurableState.SlotRole role) {
        state.frames.add(new DurableState.RuntimeFrame(node, input, role));
    }
    public void finish(DurableState.MachineOutcome outcome, CheckpointReasons.Reason reason) {
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

    @SuppressWarnings("unchecked")
    private DurableState.MachineOutcome consume(DurableState.RuntimeFrame frame,
                                                DurableState.MachineOutcome child) {
        DurablePlanNode node = frame.node;
        DurableFrameReducePolicy<DurablePlanNode> policy = (DurableFrameReducePolicy<DurablePlanNode>) DurableFrameReducePolicyRegistry.global()
                .get(node.getClass())
                .orElseThrow(() -> new IllegalStateException("Node cannot consume child outcome: " + node.getClass().getName()));
        return policy.reduce(node, frame, child, this);
    }

    private void timeoutNearestScope() {
        int timeoutIndex = -1;
        for (int index = state.frames.size() - 1; index >= 0; index--) {
            DurableState.RuntimeFrame frame = state.frames.get(index);
            if (frame.node instanceof DurablePlanNode.Control) {
                DurablePlanNode.Control control = (DurablePlanNode.Control) frame.node;
                if (control.kind() == com.team4u.framework.flow.spi.ControlKind.TIMEOUT
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

    Instant deadline() {
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
    public void waitOrPark(DurableState.RuntimeFrame frame, DurablePlanNode.Control node) {
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
                        failed(FlowDiagnosticCodes.WAIT_INTERRUPTED, "Policy wait was interrupted")),
                        CheckpointReasons.control(node.descriptor().path()));
                return;
            }
        }
        frame.wake = null;
    }
    public void commitCheckpoint(CheckpointReasons.Reason reason) {
        checkpoints.commit(reason);
    }
    public PolicyContext policyContext(final DurablePlanNode.Control node,
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
    public void waitingEvent(DurablePlanNode.Control node, DurableState.RuntimeFrame frame) {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("attempt", Integer.toString(frame.attempt));
        attrs.put("wake", frame.wake.toString());
        event(FlowObserver.Type.POLICY_WAITING, node.descriptor(), attrs);
    }
    public Map<String, String> policyAfterAttrs(DurableState.RuntimeFrame frame,
                                         DurableState.MachineOutcome child) {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("attempt", Integer.toString(frame.attempt));
        attrs.put("outcome", child.outcome().kind().name());
        return Collections.unmodifiableMap(attrs);
    }

    public void nodeStarted(DurableState.RuntimeFrame frame) {
        if (frame.observerStarted) {
            return;
        }
        frame.observerStarted = true;
        event(FlowObserver.Type.NODE_STARTED, frame.node.descriptor(),
                attributes(frame, null));
    }

    private void nodeCompleted(DurableState.RuntimeFrame frame, Outcome<?> outcome) {
        if (frame.node instanceof DurablePlanNode.Invoke) {
            return;
        }
        event(FlowObserver.Type.NODE_COMPLETED, frame.node.descriptor(),
                attributes(frame, outcome));
    }

    public void event(FlowObserver.Type type, NodeDescriptor descriptor,
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

    public Outcome<?> timeoutFailure() {
        return failed(FlowDiagnosticCodes.TIMEOUT, "Flow scope deadline elapsed");
    }

    static Outcome<?> failed(String code, String message) {
        return Outcome.failed(Failure.of(code, message));
    }

    public static Outcome<?> policyFailure(Throwable error) {
        return failed(FlowDiagnosticCodes.POLICY_EXCEPTION, describe(error));
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
}
