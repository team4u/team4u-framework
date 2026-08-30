package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Durable 执行引擎：负责驱动节点按计划执行、CAS 检查点快照落库、异常恢复与终态处理。
 *
 * @author jay.wu
 */
final class DurableRunner {

    private final DurableStore store;
    private final StateMapper stateMapper;

    DurableRunner(DurableStore store, StateMapper stateMapper) {
        this.store = Objects.requireNonNull(store, "DurableStore must not be null");
        this.stateMapper = Objects.requireNonNull(stateMapper, "StateMapper must not be null");
    }

    <O> DurableResult<O> run(DurablePlanNode plan, DurableSnapshot initialSnapshot) {
        return runInternal(plan, initialSnapshot, true);
    }

    @SuppressWarnings("unchecked")
    private <O> DurableResult<O> runInternal(DurablePlanNode plan, DurableSnapshot initialSnapshot, boolean isRoot) {
        DurableSnapshot snapshot = initialSnapshot;

        // Check if already terminal
        if (snapshot.lifecycle() != DurableLifecycle.ACTIVE) {
            return toResult(snapshot);
        }

        try {
            Object activeValue = null;
            StoredValue activeSlot = snapshot.getSlot("active");
            if (activeSlot != null) {
                activeValue = stateMapper.decode(activeSlot);
            }

            Object inputVal = null;
            StoredValue inputSlot = snapshot.getSlot("input");
            if (inputSlot != null) {
                inputVal = stateMapper.decode(inputSlot);
            }

            if (plan instanceof DurablePlanNode.SequencePlanNode) {
                DurablePlanNode.SequencePlanNode seq = (DurablePlanNode.SequencePlanNode) plan;
                return executeSequence(seq, snapshot, inputVal, activeValue, isRoot);
            } else {
                return executeSingleNode(plan, snapshot, inputVal, activeValue, isRoot);
            }
        } catch (Exception e) {
            DurableFailure failure = new DurableFailure(plan.id(), plan.path(), e.getClass().getName(), e.getMessage());
            DurableSnapshot failedSnap = snapshot.withFailed(failure);
            casSave(failedSnap, snapshot.revision());
            return toResult(failedSnap);
        }
    }

    @SuppressWarnings("unchecked")
    private <O> DurableResult<O> executeSequence(DurablePlanNode.SequencePlanNode seq,
                                                 DurableSnapshot initialSnapshot,
                                                 Object inputVal,
                                                 Object activeVal,
                                                 boolean isRoot) throws Exception {
        DurableSnapshot snapshot = initialSnapshot;
        Object currentActive = activeVal;
        List<DurablePlanNode> children = seq.children();
        int cursor = snapshot.frameState().cursor();

        Exception caughtEx = null;
        DurablePlanNode failedNode = null;

        for (int i = cursor; i < children.size(); i++) {
            // Check if cancelled externally
            DurableSnapshot latest = store.load(snapshot.flowId(), snapshot.executionId());
            if (latest != null && latest.lifecycle() == DurableLifecycle.CANCELLED) {
                return toResult(latest);
            }

            DurablePlanNode node = children.get(i);
            try {
                if (node instanceof DurablePlanNode.StepPlanNode) {
                    DurablePlanNode.StepPlanNode stepNode = (DurablePlanNode.StepPlanNode) node;
                    StepContext stepCtx = buildContext(snapshot, stepNode.id(), stepNode.path(), stepNode.address());
                    Object output = executeStepWithInterceptors(stepNode, stepCtx, currentActive);
                    if (output == null) {
                        throw new IllegalStateException("Step [" + stepNode.id() + "] returned null value");
                    }
                    currentActive = output;
                    StoredValue newActiveSlot = stateMapper.encode(currentActive);
                    DurableSnapshot nextSnap = snapshot.withCheckpoint(i + 1, "active", newActiveSlot);
                    if (!casSave(nextSnap, snapshot.revision())) {
                        return toResult(store.load(snapshot.flowId(), snapshot.executionId()));
                    }
                    snapshot = nextSnap;

                } else if (node instanceof DurablePlanNode.TapPlanNode) {
                    DurablePlanNode.TapPlanNode tapNode = (DurablePlanNode.TapPlanNode) node;
                    StepContext stepCtx = buildContext(snapshot, tapNode.id(), tapNode.path(), tapNode.address());
                    executeTapWithInterceptors(tapNode, stepCtx, currentActive);
                    StoredValue newActiveSlot = stateMapper.encode(currentActive);
                    DurableSnapshot nextSnap = snapshot.withCheckpoint(i + 1, "active", newActiveSlot);
                    if (!casSave(nextSnap, snapshot.revision())) {
                        return toResult(store.load(snapshot.flowId(), snapshot.executionId()));
                    }
                    snapshot = nextSnap;

                } else if (node instanceof DurablePlanNode.GuardPlanNode) {
                    DurablePlanNode.GuardPlanNode guardNode = (DurablePlanNode.GuardPlanNode) node;
                    boolean passed = guardNode.condition().test(currentActive);
                    if (passed) {
                        DurableSnapshot nextSnap = snapshot.withCheckpoint(i + 1, null, null);
                        if (!casSave(nextSnap, snapshot.revision())) {
                            return toResult(store.load(snapshot.flowId(), snapshot.executionId()));
                        }
                        snapshot = nextSnap;
                    } else {
                        StopReason reason = guardNode.reasonFactory().apply(currentActive);
                        if (reason == null) {
                            reason = StopReason.of("STOPPED");
                        }
                        DurableSnapshot stopSnap = snapshot.withStopped(reason);
                        casSave(stopSnap, snapshot.revision());
                        executeEnsureIfPresent(seq.ensureNode(), stopSnap, inputVal, null, reason, null);
                        return toResult(stopSnap);
                    }

                } else if (node instanceof DurablePlanNode.ChoosePlanNode) {
                    DurablePlanNode.ChoosePlanNode chooseNode = (DurablePlanNode.ChoosePlanNode) node;
                    String chosenKey = snapshot.frameState().branchChoice(chooseNode.address());
                    if (chosenKey == null) {
                        Object selectorKey = chooseNode.selector().apply(currentActive);
                        chosenKey = selectorKey != null ? String.valueOf(selectorKey) : null;
                        FrameState nextFrame = snapshot.frameState().withBranchChoice(chooseNode.address(), chosenKey);
                        DurableSnapshot branchSnap = snapshot.withFrameState(nextFrame).withRevision(snapshot.revision() + 1);
                        if (!casSave(branchSnap, snapshot.revision())) {
                            return toResult(store.load(snapshot.flowId(), snapshot.executionId()));
                        }
                        snapshot = branchSnap;
                    }

                    DurablePlanNode branchPlan = null;
                    for (Map.Entry<Object, DurablePlanNode> entry : chooseNode.branches().entrySet()) {
                        if (Objects.equals(String.valueOf(entry.getKey()), chosenKey)) {
                            branchPlan = entry.getValue();
                            break;
                        }
                    }

                    if (branchPlan != null) {
                        DurableSnapshot branchSnapshot = snapshot.withFrameState(snapshot.frameState().withCursor(0));
                        DurableResult<Object> branchRes = runInternal(branchPlan, branchSnapshot, false);
                        if (branchRes.isCompleted()) {
                            currentActive = branchRes.value();
                            snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                        } else {
                            return (DurableResult<O>) branchRes;
                        }
                    } else if (chooseNode.otherwiseBranch() != null) {
                        DurableSnapshot otherwiseSnapshot = snapshot.withFrameState(snapshot.frameState().withCursor(0));
                        DurableResult<Object> branchRes = runInternal(chooseNode.otherwiseBranch(), otherwiseSnapshot, false);
                        if (branchRes.isCompleted()) {
                            currentActive = branchRes.value();
                            snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                        } else {
                            return (DurableResult<O>) branchRes;
                        }
                    } else if (chooseNode.otherwiseStopReason() != null) {
                        StopReason reason = chooseNode.otherwiseStopReason().apply(currentActive);
                        if (reason == null) {
                            reason = StopReason.of("STOPPED");
                        }
                        DurableSnapshot stopSnap = snapshot.withStopped(reason);
                        casSave(stopSnap, snapshot.revision());
                        executeEnsureIfPresent(seq.ensureNode(), stopSnap, inputVal, null, reason, null);
                        return toResult(stopSnap);
                    } else {
                        throw new NoSuchElementException("No matching branch in choose [" + chooseNode.id() + "] for key [" + chosenKey + "]");
                    }

                    DurableSnapshot nextSnap = snapshot.withCheckpoint(i + 1, "active", stateMapper.encode(currentActive));
                    if (!casSave(nextSnap, snapshot.revision())) {
                        return toResult(store.load(snapshot.flowId(), snapshot.executionId()));
                    }
                    snapshot = nextSnap;

                } else if (node instanceof DurablePlanNode.SubflowPlanNode) {
                    DurablePlanNode.SubflowPlanNode subNode = (DurablePlanNode.SubflowPlanNode) node;
                    DurableSnapshot subSnapshot = snapshot.withFrameState(snapshot.frameState().withCursor(0));
                    DurableResult<Object> subRes = runInternal(subNode.subflowPlan(), subSnapshot, false);
                    if (subRes.isCompleted()) {
                        currentActive = subRes.value();
                        snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                        DurableSnapshot nextSnap = snapshot.withCheckpoint(i + 1, "active", stateMapper.encode(currentActive));
                        if (!casSave(nextSnap, snapshot.revision())) {
                            return toResult(store.load(snapshot.flowId(), snapshot.executionId()));
                        }
                        snapshot = nextSnap;
                    } else {
                        return (DurableResult<O>) subRes;
                    }
                }
            } catch (Exception e) {
                caughtEx = e;
                failedNode = node;
                break;
            }
        }

        // Check if body failed
        if (caughtEx != null) {
            if (seq.recoverNode() != null && !snapshot.frameState().isRecoverPhase()) {
                DurablePlanNode.RecoverPlanNode rec = seq.recoverNode();
                try {
                    FailureContext fc = new FailureContext(failedNode.id(), failedNode.path(), caughtEx);
                    FlowResult<?> recRes;
                    if (rec.isContextual()) {
                        StepContext recCtx = buildContext(snapshot, rec.id(), rec.path(), rec.address());
                        recRes = rec.contextualRecovery().recover(recCtx, inputVal, fc);
                    } else {
                        recRes = rec.recovery().recover(inputVal, fc);
                    }

                    if (recRes != null && recRes.isSucceeded()) {
                        currentActive = recRes.value();
                        StoredValue recSlot = stateMapper.encode(currentActive);
                        FrameState nextFrame = snapshot.frameState().withCursor(children.size()).withRecoverPhase(true);
                        DurableSnapshot recSnap = snapshot.withSlot("active", recSlot).withFrameState(nextFrame).withRevision(snapshot.revision() + 1);
                        if (!casSave(recSnap, snapshot.revision())) {
                            return toResult(store.load(snapshot.flowId(), snapshot.executionId()));
                        }
                        snapshot = recSnap;
                    } else if (recRes != null && recRes.isStopped()) {
                        DurableSnapshot stopSnap = snapshot.withStopped(recRes.stopReason());
                        casSave(stopSnap, snapshot.revision());
                        executeEnsureIfPresent(seq.ensureNode(), stopSnap, inputVal, null, recRes.stopReason(), null);
                        return toResult(stopSnap);
                    } else {
                        Throwable recCause = (recRes != null && recRes.isFailed()) ? recRes.failure().cause() : caughtEx;
                        DurableFailure failure = new DurableFailure(rec.id(), rec.path(),
                                recCause != null ? recCause.getClass().getName() : caughtEx.getClass().getName(),
                                recCause != null ? recCause.getMessage() : caughtEx.getMessage());
                        DurableSnapshot failedSnap = snapshot.withFailed(failure);
                        casSave(failedSnap, snapshot.revision());
                        executeEnsureIfPresent(seq.ensureNode(), failedSnap, inputVal, null, null, failure);
                        return toResult(failedSnap);
                    }
                } catch (Exception recEx) {
                    DurableFailure failure = new DurableFailure(rec.id(), rec.path(), recEx.getClass().getName(), recEx.getMessage());
                    DurableSnapshot failedSnap = snapshot.withFailed(failure);
                    casSave(failedSnap, snapshot.revision());
                    executeEnsureIfPresent(seq.ensureNode(), failedSnap, inputVal, null, null, failure);
                    return toResult(failedSnap);
                }
            } else {
                DurableFailure failure = new DurableFailure(failedNode.id(), failedNode.path(), caughtEx.getClass().getName(), caughtEx.getMessage());
                DurableSnapshot failedSnap = snapshot.withFailed(failure);
                casSave(failedSnap, snapshot.revision());
                executeEnsureIfPresent(seq.ensureNode(), failedSnap, inputVal, null, null, failure);
                return toResult(failedSnap);
            }
        }

        if (isRoot) {
            // Root sequence completed successfully
            StoredValue outSlot = stateMapper.encode(currentActive);
            DurableSnapshot compSnap = snapshot.withCompleted(outSlot);
            casSave(compSnap, snapshot.revision());
            executeEnsureIfPresent(seq.ensureNode(), compSnap, inputVal, currentActive, null, null);
            return toResult(compSnap);
        } else {
            // Inner sequence completed successfully
            executeEnsureIfPresent(seq.ensureNode(), snapshot, inputVal, currentActive, null, null);
            return DurableResult.completed(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision(), (O) currentActive);
        }
    }

    private <O> DurableResult<O> executeSingleNode(DurablePlanNode node,
                                                   DurableSnapshot snapshot,
                                                   Object inputVal,
                                                   Object activeVal,
                                                   boolean isRoot) throws Exception {
        // Wrap in sequence
        DurablePlanNode.SequencePlanNode seq = new DurablePlanNode.SequencePlanNode(
                new Flow.SequenceInfo(node.id(), node.path(), node.address()),
                Collections.singletonList(node));
        return executeSequence(seq, snapshot, inputVal, activeVal, isRoot);
    }

    private void executeEnsureIfPresent(DurablePlanNode.EnsurePlanNode ensureNode,
                                        DurableSnapshot snapshot,
                                        Object inputVal,
                                        Object outputVal,
                                        StopReason stopReason,
                                        DurableFailure failure) {
        if (ensureNode == null) {
            return;
        }
        try {
            StepContext ensCtx = buildContext(snapshot, ensureNode.id(), ensureNode.path(), ensureNode.address());
            CompletionContext<Object> cc;
            if (snapshot.lifecycle() == DurableLifecycle.COMPLETED) {
                cc = new CompletionContext<>(FlowResult.succeeded(outputVal));
            } else if (snapshot.lifecycle() == DurableLifecycle.STOPPED) {
                cc = new CompletionContext<>(FlowResult.stopped(stopReason != null ? stopReason : StopReason.of("STOPPED")));
            } else {
                Throwable cause = failure != null ? new RuntimeException(failure.message()) : new RuntimeException("Flow failed");
                cc = new CompletionContext<>(FlowResult.failed(
                        failure != null ? failure.nodeId() : ensureNode.id(),
                        failure != null ? failure.nodePath() : ensureNode.path(),
                        cause));
            }

            if (ensureNode.isContextual()) {
                ensureNode.contextualCompletionAction().onComplete(ensCtx, inputVal, cc);
            } else {
                ensureNode.completionAction().onComplete(inputVal, cc);
            }
        } catch (Exception e) {
            // Ensure error transitions to FAILED if not already failed
            if (snapshot.lifecycle() != DurableLifecycle.FAILED) {
                DurableFailure ensFailure = new DurableFailure(ensureNode.id(), ensureNode.path(), e.getClass().getName(), e.getMessage());
                DurableSnapshot failedSnap = snapshot.withFailed(ensFailure);
                casSave(failedSnap, snapshot.revision());
            }
        }
    }

    private Object executeStepWithInterceptors(DurablePlanNode.StepPlanNode stepNode, StepContext stepCtx, Object input) throws Exception {
        List<StepInterceptor> interceptors = stepNode.interceptors();
        if (interceptors.isEmpty()) {
            return stepNode.isContextual() ? stepNode.contextualStep().apply(stepCtx, input) : stepNode.step().apply(input);
        }

        StepInterceptor.Chain<Object, Object> chain = new StepInterceptor.Chain<Object, Object>() {
            private int index = 0;

            @Override
            public StepContext context() {
                return stepCtx;
            }

            @Override
            public Object input() {
                return input;
            }

            @Override
            public Object proceed(Object in) throws Exception {
                if (index < interceptors.size()) {
                    StepInterceptor interceptor = interceptors.get(index++);
                    return interceptor.intercept(this);
                }
                return stepNode.isContextual() ? stepNode.contextualStep().apply(stepCtx, in) : stepNode.step().apply(in);
            }
        };
        return chain.proceed(input);
    }

    private void executeTapWithInterceptors(DurablePlanNode.TapPlanNode tapNode, StepContext stepCtx, Object input) throws Exception {
        List<StepInterceptor> interceptors = tapNode.interceptors();
        if (interceptors.isEmpty()) {
            if (tapNode.isContextual()) {
                tapNode.contextualAction().execute(stepCtx, input);
            } else {
                tapNode.action().execute(input);
            }
            return;
        }

        StepInterceptor.Chain<Object, Object> chain = new StepInterceptor.Chain<Object, Object>() {
            private int index = 0;

            @Override
            public StepContext context() {
                return stepCtx;
            }

            @Override
            public Object input() {
                return input;
            }

            @Override
            public Object proceed(Object in) throws Exception {
                if (index < interceptors.size()) {
                    StepInterceptor interceptor = interceptors.get(index++);
                    return interceptor.intercept(this);
                }
                if (tapNode.isContextual()) {
                    tapNode.contextualAction().execute(stepCtx, in);
                } else {
                    tapNode.action().execute(in);
                }
                return in;
            }
        };
        chain.proceed(input);
    }

    private StepContext buildContext(DurableSnapshot snapshot, String nodeId, String nodePath, String address) {
        String invocId = snapshot.flowId() + ":" + snapshot.flowVersion() + ":" + snapshot.executionId() + "#" + address;
        return new StepContext() {
            @Override public String flowId() { return snapshot.flowId(); }
            @Override public String executionId() { return snapshot.executionId(); }
            @Override public String nodeId() { return nodeId; }
            @Override public String nodePath() { return nodePath; }
            @Override public String invocationId() { return invocId; }
        };
    }

    private boolean casSave(DurableSnapshot snapshot, long expectedRevision) {
        return store.save(snapshot, expectedRevision);
    }

    @SuppressWarnings("unchecked")
    private <O> DurableResult<O> toResult(DurableSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        switch (snapshot.lifecycle()) {
            case COMPLETED:
                try {
                    StoredValue outSlot = snapshot.getSlot("output");
                    if (outSlot == null) {
                        outSlot = snapshot.getSlot("active");
                    }
                    O val = outSlot != null ? (O) stateMapper.decode(outSlot) : null;
                    return DurableResult.completed(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision(), val);
                } catch (Exception e) {
                    DurableFailure f = new DurableFailure("codec", "codec", e.getClass().getName(), e.getMessage());
                    return DurableResult.failed(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision(), f);
                }
            case STOPPED:
                return DurableResult.stopped(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision(), snapshot.stopReason());
            case FAILED:
                return DurableResult.failed(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision(), snapshot.failure());
            case CANCELLED:
                return DurableResult.cancelled(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision());
            case ACTIVE:
            default:
                return DurableResult.active(snapshot.flowId(), snapshot.flowVersion(), snapshot.executionId(), snapshot.revision());
        }
    }
}
