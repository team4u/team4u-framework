package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Durable 执行引擎：基于不可变帧栈的状态机执行器，负责驱动节点按计划推进、
 * CAS 检查点快照落库、异常恢复与终态清理。
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
        DurableSnapshot snapshot = initialSnapshot;

        // 终态快照直接转换为结果
        if (snapshot.lifecycle() != DurableLifecycle.ACTIVE) {
            return toResult(snapshot);
        }

        DurablePlanNode.SequencePlanNode rootSeq = normalizeToSequence(plan);

        // 确保帧栈已初始化
        if (snapshot.frameState() == null || snapshot.frameState().frames().isEmpty()) {
            FrameState.ExecutionFrame rootFrame = FrameState.ExecutionFrame.initial(rootSeq.address());
            snapshot = snapshot.withFrameState(new FrameState(Collections.singletonList(rootFrame)));
        }

        try {
            while (snapshot.lifecycle() == DurableLifecycle.ACTIVE) {
                // 检查外部权威快照版本与生命周期：若与当前快照不一致，立即返回权威结果，不执行陈旧工作
                DurableSnapshot latest = store.load(snapshot.flowId(), snapshot.executionId());
                if (latest == null) {
                    throw new IllegalStateException("Execution [" + snapshot.executionId() + "] not found in store");
                }
                if (latest.revision() != snapshot.revision() || latest.lifecycle() != snapshot.lifecycle()) {
                    return toResult(latest);
                }

                int topIndex = snapshot.frameState().frames().size() - 1;
                FrameState.ExecutionFrame topFrame = snapshot.frameState().frames().get(topIndex);
                DurablePlanNode topNode = findNodeForFrame(rootSeq, snapshot.frameState().frames(), topIndex);
                DurablePlanNode.SequencePlanNode currentSeq = normalizeToSequence(topNode);

                FrameState.Phase phase = topFrame.phase();
                if (phase == FrameState.Phase.BODY) {
                    int cursor = topFrame.cursor();
                    List<DurablePlanNode> children = currentSeq.children();

                    if (cursor < children.size()) {
                        DurablePlanNode child = children.get(cursor);
                        String fullChildAddr = qualifyAddress(topFrame.sequenceAddress(), child.address());
                        Object currentActive = decodeSlot(snapshot, "active");

                        try {
                            if (child instanceof DurablePlanNode.StepPlanNode) {
                                DurablePlanNode.StepPlanNode stepNode = (DurablePlanNode.StepPlanNode) child;
                                String fullChildPath = qualifyPath(topFrame.pathPrefix(), stepNode.path());
                                StepContext stepCtx = buildContext(snapshot, stepNode.id(), fullChildPath, fullChildAddr);
                                Object output = executeStepWithInterceptors(stepNode, stepCtx, currentActive);
                                if (output == null) {
                                    throw new IllegalStateException("Step [" + stepNode.id() + "] returned null value");
                                }
                                StoredValue newActiveSlot = stateMapper.encode(output);
                                FrameState newFrameState = snapshot.frameState().replaceTopFrame(topFrame.withCursor(cursor + 1));
                                DurableSnapshot nextSnap = snapshot.withSlot("active", newActiveSlot)
                                        .withFrameState(newFrameState)
                                        .withRevision(snapshot.revision() + 1);
                                if (!casSave(nextSnap, snapshot.revision())) {
                                    return onCasLoss(snapshot);
                                }
                                snapshot = nextSnap;

                            } else if (child instanceof DurablePlanNode.TapPlanNode) {
                                DurablePlanNode.TapPlanNode tapNode = (DurablePlanNode.TapPlanNode) child;
                                String fullChildPath = qualifyPath(topFrame.pathPrefix(), tapNode.path());
                                StepContext stepCtx = buildContext(snapshot, tapNode.id(), fullChildPath, fullChildAddr);
                                executeTapWithInterceptors(tapNode, stepCtx, currentActive);
                                StoredValue newActiveSlot = stateMapper.encode(currentActive);
                                FrameState newFrameState = snapshot.frameState().replaceTopFrame(topFrame.withCursor(cursor + 1));
                                DurableSnapshot nextSnap = snapshot.withSlot("active", newActiveSlot)
                                        .withFrameState(newFrameState)
                                        .withRevision(snapshot.revision() + 1);
                                if (!casSave(nextSnap, snapshot.revision())) {
                                    return onCasLoss(snapshot);
                                }
                                snapshot = nextSnap;

                            } else if (child instanceof DurablePlanNode.GuardPlanNode) {
                                DurablePlanNode.GuardPlanNode guardNode = (DurablePlanNode.GuardPlanNode) child;
                                boolean passed = guardNode.condition().test(currentActive);
                                if (passed) {
                                    FrameState newFrameState = snapshot.frameState().replaceTopFrame(topFrame.withCursor(cursor + 1));
                                    DurableSnapshot nextSnap = snapshot.withFrameState(newFrameState)
                                            .withRevision(snapshot.revision() + 1);
                                    if (!casSave(nextSnap, snapshot.revision())) {
                                        return onCasLoss(snapshot);
                                    }
                                    snapshot = nextSnap;
                                } else {
                                    StopReason reason = guardNode.reasonFactory().apply(currentActive);
                                    if (reason == null) {
                                        throw new IllegalStateException("Guard reasonFactory returned null StopReason for node [" +
                                                guardNode.id() + "]");
                                    }
                                    DurableResult<O> stopRes = handleStopped(snapshot, rootSeq, currentSeq, topIndex, topFrame, reason);
                                    if (stopRes != null) {
                                        return stopRes;
                                    }
                                    snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                                }

                            } else if (child instanceof DurablePlanNode.ChoosePlanNode) {
                                DurablePlanNode.ChoosePlanNode chooseNode = (DurablePlanNode.ChoosePlanNode) child;
                                String chosenBranchToken = topFrame.selectedBranch();

                                if (chosenBranchToken == null) {
                                    Object selectorKey = chooseNode.selector().apply(currentActive);
                                    if (selectorKey == null) {
                                        throw new IllegalStateException("Choose selector returned null key for node [" + chooseNode.id() + "]");
                                    }

                                    DurablePlanNode matchedBranch = null;
                                    String selectedToken = null;
                                    int bIdx = 0;
                                    for (Map.Entry<Object, DurablePlanNode> entry : chooseNode.branches().entrySet()) {
                                        if (Objects.equals(entry.getKey(), selectorKey)) {
                                            matchedBranch = entry.getValue();
                                            selectedToken = "case:" + bIdx;
                                            break;
                                        }
                                        bIdx++;
                                    }

                                    if (matchedBranch == null && chooseNode.otherwiseBranch() != null) {
                                        matchedBranch = chooseNode.otherwiseBranch();
                                        selectedToken = "otherwise";
                                    }

                                    if (matchedBranch != null) {
                                        FrameState.ExecutionFrame updatedTop = topFrame.withSelectedBranch(selectedToken);
                                        String branchSeqAddr = fullChildAddr + "/" + selectedToken;
                                        String branchSlotName = "input:" + branchSeqAddr;
                                        String branchPathPrefix = qualifyPath(topFrame.pathPrefix(), chooseNode.path());
                                        StoredValue branchInputSlot = stateMapper.encode(currentActive);
                                        FrameState.ExecutionFrame branchFrame = new FrameState.ExecutionFrame(
                                                branchSeqAddr, 0, branchSlotName, null, FrameState.Phase.BODY, null, null, branchPathPrefix);
                                        FrameState newFrameState = snapshot.frameState().replaceTopFrame(updatedTop).pushFrame(branchFrame);
                                        DurableSnapshot nextSnap = snapshot.withSlot(branchSlotName, branchInputSlot)
                                                .withSlot("active", branchInputSlot)
                                                .withFrameState(newFrameState)
                                                .withRevision(snapshot.revision() + 1);
                                        if (!casSave(nextSnap, snapshot.revision())) {
                                            return onCasLoss(snapshot);
                                        }
                                        snapshot = nextSnap;
                                    } else if (chooseNode.otherwiseStopReason() != null) {
                                        StopReason reason = chooseNode.otherwiseStopReason().apply(currentActive);
                                        if (reason == null) {
                                            throw new IllegalStateException("Choose otherwiseStopReason returned null StopReason for node [" +
                                                    chooseNode.id() + "]");
                                        }
                                        DurableResult<O> stopRes = handleStopped(snapshot, rootSeq, currentSeq, topIndex, topFrame, reason);
                                        if (stopRes != null) {
                                            return stopRes;
                                        }
                                        snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                                    } else {
                                        throw new NoSuchElementException("No matching branch in choose [" + chooseNode.id() + "] for key [" + selectorKey + "]");
                                    }
                                } else {
                                    // 已经持久化了分支选择，若子分支尚未入栈则压入
                                    DurablePlanNode branchPlan = resolveBranch(chooseNode, chosenBranchToken);
                                    if (branchPlan != null) {
                                        String branchSeqAddr = fullChildAddr + "/" + chosenBranchToken;
                                        String branchSlotName = "input:" + branchSeqAddr;
                                        String branchPathPrefix = qualifyPath(topFrame.pathPrefix(), chooseNode.path());
                                        StoredValue branchInputSlot = snapshot.getSlot(branchSlotName);
                                        if (branchInputSlot == null) {
                                            branchInputSlot = stateMapper.encode(currentActive);
                                        }
                                        FrameState.ExecutionFrame branchFrame = new FrameState.ExecutionFrame(
                                                branchSeqAddr, 0, branchSlotName, null, FrameState.Phase.BODY, null, null, branchPathPrefix);
                                        FrameState newFrameState = snapshot.frameState().pushFrame(branchFrame);
                                        DurableSnapshot nextSnap = snapshot.withSlot(branchSlotName, branchInputSlot)
                                                .withFrameState(newFrameState)
                                                .withRevision(snapshot.revision() + 1);
                                        if (!casSave(nextSnap, snapshot.revision())) {
                                            return onCasLoss(snapshot);
                                        }
                                        snapshot = nextSnap;
                                    } else {
                                        throw new NoSuchElementException("Cannot resolve branch [" + chosenBranchToken + "] for choose [" + chooseNode.id() + "]");
                                    }
                                }

                            } else if (child instanceof DurablePlanNode.SubflowPlanNode) {
                                DurablePlanNode.SubflowPlanNode subflowNode = (DurablePlanNode.SubflowPlanNode) child;
                                String subflowSeqAddr = fullChildAddr;
                                String subflowSlotName = "input:" + subflowSeqAddr;
                                String subflowPathPrefix = qualifyPath(topFrame.pathPrefix(), subflowNode.path());
                                StoredValue subflowInputSlot = stateMapper.encode(currentActive);
                                FrameState.ExecutionFrame subflowFrame = new FrameState.ExecutionFrame(
                                        subflowSeqAddr, 0, subflowSlotName, null, FrameState.Phase.BODY, null, null, subflowPathPrefix);
                                FrameState newFrameState = snapshot.frameState().pushFrame(subflowFrame);
                                DurableSnapshot nextSnap = snapshot.withSlot(subflowSlotName, subflowInputSlot)
                                        .withSlot("active", subflowInputSlot)
                                        .withFrameState(newFrameState)
                                        .withRevision(snapshot.revision() + 1);
                                if (!casSave(nextSnap, snapshot.revision())) {
                                    return onCasLoss(snapshot);
                                }
                                snapshot = nextSnap;
                            }
                        } catch (Throwable t) {
                            if (t instanceof Error) {
                                throw (Error) t;
                            }
                            if (t instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                            Exception e = (Exception) t;
                            String fullChildPath = qualifyPath(topFrame.pathPrefix(), child.path());
                            DurableFailure failure = new DurableFailure(child.id(), fullChildPath, e.getClass().getName(), e.getMessage());
                            DurableSnapshot snapWithRetry = snapshot.retryFrameState() != null ? snapshot : snapshot.withRetryFrameState(snapshot.frameState());
                            DurableResult<O> failRes = handleFailed(snapWithRetry, rootSeq, currentSeq, topIndex, topFrame, failure, e);
                            if (failRes != null) {
                                return failRes;
                            }
                            snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                        }

                    } else {
                        // cursor >= children.size(): 该序列主体节点已全部完成
                        if (currentSeq.ensureNode() != null) {
                            FrameState.ExecutionFrame ensureFrame = topFrame.withEnsurePhase(null, null);
                            FrameState newFrameState = snapshot.frameState().replaceTopFrame(ensureFrame);
                            DurableSnapshot nextSnap = snapshot.withFrameState(newFrameState)
                                    .withRevision(snapshot.revision() + 1);
                            if (!casSave(nextSnap, snapshot.revision())) {
                                return onCasLoss(snapshot);
                            }
                            snapshot = nextSnap;
                        } else {
                            if (topIndex == 0) {
                                // 根流程完成
                                StoredValue outSlot = snapshot.getSlot("active");
                                DurableSnapshot compSnap = snapshot.withCompleted(outSlot);
                                if (!casSave(compSnap, snapshot.revision())) {
                                    return onCasLoss(snapshot);
                                }
                                return toResult(compSnap);
                            } else {
                                // 内部子流程/分支完成：出栈并推进父帧游标
                                FrameState.ExecutionFrame parentFrame = snapshot.frameState().frames().get(topIndex - 1);
                                FrameState.ExecutionFrame updatedParent = parentFrame.withCursor(parentFrame.cursor() + 1).withSelectedBranch(null);
                                FrameState newFrameState = snapshot.frameState().popFrame().replaceTopFrame(updatedParent);
                                DurableSnapshot nextSnap = snapshot.withFrameState(newFrameState)
                                        .withRevision(snapshot.revision() + 1);
                                if (!casSave(nextSnap, snapshot.revision())) {
                                    return onCasLoss(snapshot);
                                }
                                snapshot = nextSnap;
                            }
                        }
                    }

                } else if (phase == FrameState.Phase.RECOVER) {
                    DurablePlanNode.RecoverPlanNode rec = currentSeq.recoverNode();
                    if (rec == null) {
                        DurableFailure failure = topFrame.pendingFailure();
                        DurableResult<O> failRes = unwindFailure(snapshot, rootSeq, currentSeq, topIndex, topFrame, failure, null);
                        if (failRes != null) {
                            return failRes;
                        }
                        snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                        continue;
                    }

                    Object scopeInput = decodeSlot(snapshot, topFrame.scopeInputSlot());
                    DurableFailure prevFailure = topFrame.pendingFailure();
                    Throwable origCause = (prevFailure != null && prevFailure.message() != null)
                            ? new RuntimeException(prevFailure.message()) : new RuntimeException("Flow failed");
                    String fullRecPath = qualifyPath(topFrame.pathPrefix(), rec.path());
                    FailureContext fc = new FailureContext(
                            prevFailure != null ? prevFailure.nodeId() : rec.id(),
                            prevFailure != null ? prevFailure.nodePath() : fullRecPath,
                            origCause);

                    String fullRecAddr = qualifyAddress(topFrame.sequenceAddress(), rec.address());
                    StepContext recCtx = buildContext(snapshot, rec.id(), fullRecPath, fullRecAddr);

                    try {
                        FlowResult<?> recRes;
                        if (rec.isContextual()) {
                            recRes = rec.contextualRecovery().recover(recCtx, scopeInput, fc);
                        } else {
                            recRes = rec.recovery().recover(scopeInput, fc);
                        }

                        if (recRes == null) {
                            throw new IllegalStateException("Recover [" + rec.id() + "] returned null FlowResult");
                        }

                        if (recRes.isSucceeded()) {
                            Object recVal = recRes.value();
                            StoredValue recSlot = stateMapper.encode(recVal);
                            // The failure has been handled. A later failure must establish its own
                            // retry checkpoint instead of reusing the recovered node's frame stack.
                            snapshot = snapshot.withRetryFrameState(null);

                            if (currentSeq.ensureNode() != null) {
                                FrameState.ExecutionFrame ensureFrame = topFrame.withEnsurePhase(null, null);
                                FrameState newFrameState = snapshot.frameState().replaceTopFrame(ensureFrame);
                                DurableSnapshot nextSnap = snapshot.withSlot("active", recSlot)
                                        .withFrameState(newFrameState)
                                        .withRevision(snapshot.revision() + 1);
                                if (!casSave(nextSnap, snapshot.revision())) {
                                    return onCasLoss(snapshot);
                                }
                                snapshot = nextSnap;
                            } else {
                                if (topIndex == 0) {
                                    DurableSnapshot compSnap = snapshot.withSlot("active", recSlot).withCompleted(recSlot);
                                    if (!casSave(compSnap, snapshot.revision())) {
                                        return onCasLoss(snapshot);
                                    }
                                    return toResult(compSnap);
                                } else {
                                    FrameState.ExecutionFrame parentFrame = snapshot.frameState().frames().get(topIndex - 1);
                                    FrameState.ExecutionFrame updatedParent = parentFrame.withCursor(parentFrame.cursor() + 1).withSelectedBranch(null);
                                    FrameState newFrameState = snapshot.frameState().popFrame().replaceTopFrame(updatedParent);
                                    DurableSnapshot nextSnap = snapshot.withSlot("active", recSlot)
                                            .withFrameState(newFrameState)
                                            .withRevision(snapshot.revision() + 1);
                                    if (!casSave(nextSnap, snapshot.revision())) {
                                        return onCasLoss(snapshot);
                                    }
                                    snapshot = nextSnap;
                                }
                            }

                        } else if (recRes.isStopped()) {
                            StopReason reason = recRes.stopReason();
                            DurableResult<O> stopRes = handleStopped(snapshot, rootSeq, currentSeq, topIndex, topFrame, reason);
                            if (stopRes != null) {
                                return stopRes;
                            }
                            snapshot = store.load(snapshot.flowId(), snapshot.executionId());

                        } else {
                            // Recover returned FAILED
                            Throwable recCause = recRes.failure() != null ? recRes.failure().cause() : null;
                            if (recCause != null && recCause != origCause) {
                                recCause.addSuppressed(origCause);
                            }
                            DurableFailure failure = new DurableFailure(rec.id(), fullRecPath,
                                    recCause != null ? recCause.getClass().getName() : origCause.getClass().getName(),
                                    recCause != null ? recCause.getMessage() : origCause.getMessage());
                            DurableSnapshot snapForRecFail = snapshot.withRetryFrameState(snapshot.frameState());
                            DurableResult<O> failRes = unwindFailure(snapForRecFail, rootSeq, currentSeq, topIndex, topFrame, failure, recCause != null ? recCause : origCause);
                            if (failRes != null) {
                                return failRes;
                            }
                            snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                        }

                    } catch (Throwable t) {
                        if (t instanceof Error) {
                            throw (Error) t;
                        }
                        if (t instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        Exception recEx = (Exception) t;
                        if (recEx != origCause) {
                            recEx.addSuppressed(origCause);
                        }
                        DurableFailure failure = recEx == origCause && prevFailure != null
                                ? prevFailure
                                : new DurableFailure(rec.id(), fullRecPath,
                                recEx.getClass().getName(), recEx.getMessage());
                        DurableSnapshot snapForRecFail = snapshot.withRetryFrameState(snapshot.frameState());
                        DurableResult<O> failRes = unwindFailure(snapForRecFail, rootSeq, currentSeq, topIndex, topFrame, failure, recEx);
                        if (failRes != null) {
                            return failRes;
                        }
                        snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                    }

                } else if (phase == FrameState.Phase.ENSURE) {
                    DurablePlanNode.EnsurePlanNode ensureNode = currentSeq.ensureNode();
                    Object scopeInput = decodeSlot(snapshot, topFrame.scopeInputSlot());
                    DurableFailure pendingFail = topFrame.pendingFailure();
                    StopReason pendingStop = topFrame.pendingStopReason();
                    Object currentActive = decodeSlot(snapshot, "active");

                    CompletionContext<Object> cc;
                    if (pendingFail != null) {
                        Throwable cause = new RuntimeException(pendingFail.message());
                        cc = new CompletionContext<>(FlowResult.failed(pendingFail.nodeId(), pendingFail.nodePath(), cause));
                    } else if (pendingStop != null) {
                        cc = new CompletionContext<>(FlowResult.stopped(pendingStop));
                    } else {
                        cc = new CompletionContext<>(FlowResult.succeeded(currentActive));
                    }

                    String fullEnsAddr = qualifyAddress(topFrame.sequenceAddress(), ensureNode.address());
                    String fullEnsPath = qualifyPath(topFrame.pathPrefix(), ensureNode.path());
                    StepContext ensCtx = buildContext(snapshot, ensureNode.id(), fullEnsPath, fullEnsAddr);
                    Throwable ensureEx = null;
                    try {
                        if (ensureNode.isContextual()) {
                            ensureNode.contextualCompletionAction().onComplete(ensCtx, scopeInput, cc);
                        } else {
                            ensureNode.completionAction().onComplete(scopeInput, cc);
                        }
                    } catch (Throwable t) {
                        if (t instanceof Error) {
                            throw (Error) t;
                        }
                        if (t instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        ensureEx = t;
                    }

                    // 合并 Ensure 异常
                    if (ensureEx != null) {
                        if (pendingFail != null) {
                            // 原已有失败，原失败为主因，ensure 异常加入 suppressed，retryFrameState 保持原失败不变
                            Throwable mainCause = new RuntimeException(pendingFail.message());
                            mainCause.addSuppressed(ensureEx);
                            pendingFail = new DurableFailure(pendingFail.nodeId(), pendingFail.nodePath(),
                                    pendingFail.errorType(), pendingFail.message());
                        } else {
                            // 原为成功或停止，ensure 异常转换结果为 FAILED，retryFrameState 设为当前 ENSURE 帧
                            pendingFail = new DurableFailure(ensureNode.id(), fullEnsPath,
                                    ensureEx.getClass().getName(), ensureEx.getMessage());
                            pendingStop = null;
                            snapshot = snapshot.withRetryFrameState(snapshot.frameState());
                        }
                    }

                    if (pendingFail != null) {
                        if (topIndex == 0) {
                            DurableSnapshot failSnap = snapshot.withFailed(pendingFail);
                            if (!casSave(failSnap, snapshot.revision())) {
                                return onCasLoss(snapshot);
                            }
                            return toResult(failSnap);
                        } else {
                            // 内部帧 Ensure 完成后，弹出并向父帧传播失败
                            FrameState newFrameState = snapshot.frameState().popFrame();
                            snapshot = snapshot.withFrameState(newFrameState);
                            int parentIndex = topIndex - 1;
                            FrameState.ExecutionFrame parentFrame = newFrameState.frames().get(parentIndex);
                            DurablePlanNode parentNode = findNodeForFrame(rootSeq, newFrameState.frames(), parentIndex);
                            DurablePlanNode.SequencePlanNode parentSeq = normalizeToSequence(parentNode);
                            DurableResult<O> failRes = handleFailed(snapshot, rootSeq, parentSeq, parentIndex, parentFrame, pendingFail, null);
                            if (failRes != null) {
                                return failRes;
                            }
                            snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                        }

                    } else if (pendingStop != null) {
                        if (topIndex == 0) {
                            DurableSnapshot stopSnap = snapshot.withStopped(pendingStop);
                            if (!casSave(stopSnap, snapshot.revision())) {
                                return onCasLoss(snapshot);
                            }
                            return toResult(stopSnap);
                        } else {
                            FrameState newFrameState = snapshot.frameState().popFrame();
                            snapshot = snapshot.withFrameState(newFrameState);
                            int parentIndex = topIndex - 1;
                            FrameState.ExecutionFrame parentFrame = newFrameState.frames().get(parentIndex);
                            DurablePlanNode parentNode = findNodeForFrame(rootSeq, newFrameState.frames(), parentIndex);
                            DurablePlanNode.SequencePlanNode parentSeq = normalizeToSequence(parentNode);
                            DurableResult<O> stopRes = handleStopped(snapshot, rootSeq, parentSeq, parentIndex, parentFrame, pendingStop);
                            if (stopRes != null) {
                                return stopRes;
                            }
                            snapshot = store.load(snapshot.flowId(), snapshot.executionId());
                        }

                    } else {
                        // 正常成功
                        if (topIndex == 0) {
                            StoredValue outSlot = snapshot.getSlot("active");
                            DurableSnapshot compSnap = snapshot.withCompleted(outSlot);
                            if (!casSave(compSnap, snapshot.revision())) {
                                return onCasLoss(snapshot);
                            }
                            return toResult(compSnap);
                        } else {
                            FrameState.ExecutionFrame parentFrame = snapshot.frameState().frames().get(topIndex - 1);
                            FrameState.ExecutionFrame updatedParent = parentFrame.withCursor(parentFrame.cursor() + 1).withSelectedBranch(null);
                            FrameState newFrameState = snapshot.frameState().popFrame().replaceTopFrame(updatedParent);
                            DurableSnapshot nextSnap = snapshot.withFrameState(newFrameState)
                                    .withRevision(snapshot.revision() + 1);
                            if (!casSave(nextSnap, snapshot.revision())) {
                                return onCasLoss(snapshot);
                            }
                            snapshot = nextSnap;
                        }
                    }
                }
            }

            return toResult(snapshot);

        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Exception e = (Exception) t;
            DurableFailure failure = new DurableFailure(plan.id(), plan.path(), e.getClass().getName(), e.getMessage());
            DurableSnapshot failedSnap = snapshot.withFailed(failure);
            if (!casSave(failedSnap, snapshot.revision())) {
                return onCasLoss(snapshot);
            }
            return toResult(failedSnap);
        }
    }

    private <O> DurableResult<O> handleFailed(DurableSnapshot snapshot,
                                              DurablePlanNode.SequencePlanNode rootSeq,
                                              DurablePlanNode.SequencePlanNode seq,
                                              int frameIndex,
                                              FrameState.ExecutionFrame frame,
                                              DurableFailure failure,
                                              Throwable cause) {
        if (seq.recoverNode() != null && frame.phase() != FrameState.Phase.RECOVER && frame.phase() != FrameState.Phase.ENSURE) {
            FrameState.ExecutionFrame recFrame = frame.withRecoverPhase(failure);
            FrameState newFrameState = snapshot.frameState().replaceTopFrame(recFrame);
            DurableSnapshot nextSnap = snapshot.withFrameState(newFrameState)
                    .withRevision(snapshot.revision() + 1);
            if (!casSave(nextSnap, snapshot.revision())) {
                return onCasLoss(snapshot);
            }
            return null;
        }

        return unwindFailure(snapshot, rootSeq, seq, frameIndex, frame, failure, cause);
    }

    private <O> DurableResult<O> unwindFailure(DurableSnapshot snapshot,
                                               DurablePlanNode.SequencePlanNode rootSeq,
                                               DurablePlanNode.SequencePlanNode seq,
                                               int frameIndex,
                                               FrameState.ExecutionFrame frame,
                                               DurableFailure failure,
                                               Throwable cause) {
        if (seq.ensureNode() != null && frame.phase() != FrameState.Phase.ENSURE) {
            FrameState.ExecutionFrame ensFrame = frame.withEnsurePhase(failure, null);
            FrameState newFrameState = snapshot.frameState().replaceTopFrame(ensFrame);
            DurableSnapshot nextSnap = snapshot.withFrameState(newFrameState)
                    .withRevision(snapshot.revision() + 1);
            if (!casSave(nextSnap, snapshot.revision())) {
                return onCasLoss(snapshot);
            }
            return null;
        }

        if (frameIndex == 0) {
            DurableSnapshot failedSnap = snapshot.withFailed(failure);
            if (!casSave(failedSnap, snapshot.revision())) {
                return onCasLoss(snapshot);
            }
            return toResult(failedSnap);
        } else {
            // 出栈并向父帧传播失败
            FrameState newFrameState = snapshot.frameState().popFrame();
            DurableSnapshot nextSnap = snapshot.withFrameState(newFrameState);
            int parentIndex = frameIndex - 1;
            FrameState.ExecutionFrame parentFrame = newFrameState.frames().get(parentIndex);
            DurablePlanNode parentNode = findNodeForFrame(rootSeq, newFrameState.frames(), parentIndex);
            return handleFailed(nextSnap, rootSeq, normalizeToSequence(parentNode), parentIndex, parentFrame, failure, cause);
        }
    }

    private <O> DurableResult<O> handleStopped(DurableSnapshot snapshot,
                                               DurablePlanNode.SequencePlanNode rootSeq,
                                               DurablePlanNode.SequencePlanNode seq,
                                               int frameIndex,
                                               FrameState.ExecutionFrame frame,
                                               StopReason reason) {
        if (seq.ensureNode() != null && frame.phase() != FrameState.Phase.ENSURE) {
            FrameState.ExecutionFrame ensFrame = frame.withEnsurePhase(null, reason);
            FrameState newFrameState = snapshot.frameState().replaceTopFrame(ensFrame);
            DurableSnapshot nextSnap = snapshot.withFrameState(newFrameState)
                    .withRevision(snapshot.revision() + 1);
            if (!casSave(nextSnap, snapshot.revision())) {
                return onCasLoss(snapshot);
            }
            return null;
        }

        if (frameIndex == 0) {
            DurableSnapshot stopSnap = snapshot.withStopped(reason);
            if (!casSave(stopSnap, snapshot.revision())) {
                return onCasLoss(snapshot);
            }
            return toResult(stopSnap);
        } else {
            FrameState newFrameState = snapshot.frameState().popFrame();
            DurableSnapshot nextSnap = snapshot.withFrameState(newFrameState);
            int parentIndex = frameIndex - 1;
            FrameState.ExecutionFrame parentFrame = newFrameState.frames().get(parentIndex);
            DurablePlanNode parentNode = findNodeForFrame(rootSeq, newFrameState.frames(), parentIndex);
            return handleStopped(nextSnap, rootSeq, normalizeToSequence(parentNode), parentIndex, parentFrame, reason);
        }
    }

    private DurablePlanNode.SequencePlanNode normalizeToSequence(DurablePlanNode plan) {
        if (plan instanceof DurablePlanNode.SequencePlanNode) {
            return (DurablePlanNode.SequencePlanNode) plan;
        }
        return new DurablePlanNode.SequencePlanNode(
                new Flow.SequenceInfo(plan.id(), plan.path(), plan.address()),
                Collections.singletonList(plan));
    }

    private DurablePlanNode findNodeForFrame(DurablePlanNode rootPlan, List<FrameState.ExecutionFrame> frames, int targetFrameIndex) {
        DurablePlanNode current = rootPlan;
        for (int i = 0; i < targetFrameIndex; i++) {
            FrameState.ExecutionFrame frame = frames.get(i);
            DurablePlanNode.SequencePlanNode seq = normalizeToSequence(current);
            int cursor = frame.cursor();
            if (cursor < seq.children().size()) {
                DurablePlanNode child = seq.children().get(cursor);
                if (child instanceof DurablePlanNode.SubflowPlanNode) {
                    current = ((DurablePlanNode.SubflowPlanNode) child).subflowPlan();
                } else if (child instanceof DurablePlanNode.ChoosePlanNode) {
                    current = resolveBranch((DurablePlanNode.ChoosePlanNode) child, frame.selectedBranch());
                } else {
                    current = child;
                }
            }
        }
        return current;
    }

    private DurablePlanNode resolveBranch(DurablePlanNode.ChoosePlanNode chooseNode, String selectedBranch) {
        if (selectedBranch == null) {
            return null;
        }
        if ("otherwise".equals(selectedBranch)) {
            return chooseNode.otherwiseBranch();
        }
        if (selectedBranch.startsWith("case:")) {
            try {
                int idx = Integer.parseInt(selectedBranch.substring("case:".length()));
                int i = 0;
                for (DurablePlanNode branch : chooseNode.branches().values()) {
                    if (i == idx) {
                        return branch;
                    }
                    i++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String qualifyPath(String prefix, String nodePath) {
        if (prefix == null || prefix.isEmpty()) {
            return nodePath != null ? nodePath : "";
        }
        if (nodePath == null || nodePath.isEmpty()) {
            return prefix;
        }
        return prefix + "/" + nodePath;
    }

    private String qualifyAddress(String parentSequenceAddress, String relativeAddress) {
        if (parentSequenceAddress == null || parentSequenceAddress.isEmpty() || parentSequenceAddress.equals("/")) {
            return relativeAddress;
        }
        if (relativeAddress.startsWith("/")) {
            return parentSequenceAddress + relativeAddress;
        }
        return parentSequenceAddress + "/" + relativeAddress;
    }

    private Object decodeSlot(DurableSnapshot snapshot, String slotName) throws Exception {
        if (slotName == null) {
            return null;
        }
        StoredValue slot = snapshot.getSlot(slotName);
        if (slot == null) {
            return null;
        }
        return stateMapper.decode(slot);
    }

    private Object executeStepWithInterceptors(DurablePlanNode.StepPlanNode stepNode,
                                               StepContext stepCtx,
                                               Object input) throws Exception {
        return proceedStep(stepNode, stepCtx, stepNode.interceptors(), 0, input);
    }

    private Object proceedStep(DurablePlanNode.StepPlanNode stepNode,
                               StepContext stepCtx,
                               List<StepInterceptor> interceptors,
                               int index,
                               Object input) throws Exception {
        if (input == null) {
            throw new IllegalArgumentException("Step input passed to proceed must not be null");
        }
        if (index < interceptors.size()) {
            StepInterceptor interceptor = interceptors.get(index);
            Object output = interceptor.intercept(new StepInterceptor.Chain<Object, Object>() {
                @Override
                public StepContext context() {
                    return stepCtx;
                }

                @Override
                public Object input() {
                    return input;
                }

                @Override
                public Object proceed(Object nextInput) throws Exception {
                    return proceedStep(stepNode, stepCtx, interceptors, index + 1, nextInput);
                }
            });
            if (output == null) {
                throw new IllegalStateException("StepInterceptor returned null output for node [" +
                        stepNode.id() + "]");
            }
            return output;
        }
        return stepNode.isContextual()
                ? stepNode.contextualStep().apply(stepCtx, input)
                : stepNode.step().apply(input);
    }

    private void executeTapWithInterceptors(DurablePlanNode.TapPlanNode tapNode,
                                            StepContext stepCtx,
                                            Object input) throws Exception {
        Object output = proceedTap(tapNode, stepCtx, tapNode.interceptors(), 0, input);
        if (output == null) {
            throw new IllegalStateException("StepInterceptor returned null output for tap node [" +
                    tapNode.id() + "]");
        }
    }

    private Object proceedTap(DurablePlanNode.TapPlanNode tapNode,
                              StepContext stepCtx,
                              List<StepInterceptor> interceptors,
                              int index,
                              Object input) throws Exception {
        if (input == null) {
            throw new IllegalArgumentException("Tap input passed to proceed must not be null");
        }
        if (index < interceptors.size()) {
            StepInterceptor interceptor = interceptors.get(index);
            Object output = interceptor.intercept(new StepInterceptor.Chain<Object, Object>() {
                @Override
                public StepContext context() {
                    return stepCtx;
                }

                @Override
                public Object input() {
                    return input;
                }

                @Override
                public Object proceed(Object nextInput) throws Exception {
                    return proceedTap(tapNode, stepCtx, interceptors, index + 1, nextInput);
                }
            });
            if (output == null) {
                throw new IllegalStateException("StepInterceptor returned null output for tap node [" +
                        tapNode.id() + "]");
            }
            return output;
        }
        if (tapNode.isContextual()) {
            tapNode.contextualAction().execute(stepCtx, input);
        } else {
            tapNode.action().execute(input);
        }
        return input;
    }

    private StepContext buildContext(DurableSnapshot snapshot, String nodeId, String nodePath, String fullAddress) {
        String invocId = snapshot.flowId() + ":" + snapshot.flowVersion() + ":" + snapshot.executionId() + "#" + fullAddress;
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

    private <O> DurableResult<O> onCasLoss(DurableSnapshot snapshot) {
        DurableSnapshot authoritative = store.load(snapshot.flowId(), snapshot.executionId());
        if (authoritative == null) {
            throw new IllegalStateException("Execution [" + snapshot.executionId() +
                    "] not found in flow [" + snapshot.flowId() + "] after CAS conflict");
        }
        return toResult(authoritative);
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
