package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 将不可变 {@link Flow} 编译为 Durable 执行计划的 Projection 实现。
 *
 * @author jay.wu
 */
final class DurablePlanCompiler implements Flow.Projection<DurablePlanNode> {

    static final DurablePlanCompiler INSTANCE = new DurablePlanCompiler();

    @Override
    public DurablePlanNode projectSequence(Flow.SequenceInfo info, List<DurablePlanNode> children) {
        return new DurablePlanNode.SequencePlanNode(info, children);
    }

    @Override
    public <T, R1> DurablePlanNode projectStep(Flow.StepInfo info, Step<T, R1> step, Step.Contextual<T, R1> contextualStep, List<StepInterceptor> interceptors) {
        return new DurablePlanNode.StepPlanNode(info, step, contextualStep, interceptors);
    }

    @Override
    public <T> DurablePlanNode projectTap(Flow.TapInfo info, Action<T> action, Action.Contextual<T> contextualAction, List<StepInterceptor> interceptors) {
        return new DurablePlanNode.TapPlanNode(info, action, contextualAction, interceptors);
    }

    @Override
    public <T> DurablePlanNode projectGuard(Flow.GuardInfo info, Condition<T> condition, Function<T, StopReason> reasonFactory) {
        return new DurablePlanNode.GuardPlanNode(info, condition, reasonFactory);
    }

    @Override
    public <T, K, R1> DurablePlanNode projectChoose(Flow.ChooseInfo<K> info, Function<T, K> selector, Map<K, DurablePlanNode> branches, DurablePlanNode otherwiseBranch, Function<T, StopReason> otherwiseStopReason) {
        return new DurablePlanNode.ChoosePlanNode(info, selector, branches, otherwiseBranch, otherwiseStopReason);
    }

    @Override
    public <T, R1> DurablePlanNode projectSubflow(Flow.SubflowInfo info, Flow<T, R1> subflow, DurablePlanNode subflowProjection) {
        return new DurablePlanNode.SubflowPlanNode(info, subflowProjection);
    }

    @Override
    public <T, R1> DurablePlanNode projectRecover(Flow.RecoverInfo info, DurablePlanNode body, Recovery<T, R1> recovery, Recovery.Contextual<T, R1> contextualRecovery) {
        DurablePlanNode.RecoverPlanNode rec = new DurablePlanNode.RecoverPlanNode(info, recovery, contextualRecovery);
        if (body instanceof DurablePlanNode.SequencePlanNode) {
            ((DurablePlanNode.SequencePlanNode) body).setRecoverNode(rec);
            return body;
        }
        List<DurablePlanNode> list = new ArrayList<>();
        list.add(body);
        DurablePlanNode.SequencePlanNode seq = new DurablePlanNode.SequencePlanNode(
                new Flow.SequenceInfo(info.id(), info.path(), info.address()), list);
        seq.setRecoverNode(rec);
        return seq;
    }

    @Override
    public <T, R1> DurablePlanNode projectEnsure(Flow.EnsureInfo info, DurablePlanNode body, CompletionAction<T, R1> completionAction, CompletionAction.Contextual<T, R1> contextualCompletionAction) {
        DurablePlanNode.EnsurePlanNode ens = new DurablePlanNode.EnsurePlanNode(info, completionAction, contextualCompletionAction);
        if (body instanceof DurablePlanNode.SequencePlanNode) {
            ((DurablePlanNode.SequencePlanNode) body).setEnsureNode(ens);
            return body;
        }
        List<DurablePlanNode> list = new ArrayList<>();
        list.add(body);
        DurablePlanNode.SequencePlanNode seq = new DurablePlanNode.SequencePlanNode(
                new Flow.SequenceInfo(info.id(), info.path(), info.address()), list);
        seq.setEnsureNode(ens);
        return seq;
    }
}
