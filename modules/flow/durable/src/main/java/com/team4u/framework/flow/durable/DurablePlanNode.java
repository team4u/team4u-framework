package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 编译后的持久化节点执行树结构。
 *
 * @author jay.wu
 */
interface DurablePlanNode {

    String id();

    String path();

    String address();

    NodeKind kind();

    final class StepPlanNode implements DurablePlanNode {
        private final String id;
        private final String path;
        private final String address;
        private final boolean contextual;
        private final Step<Object, Object> step;
        private final Step.Contextual<Object, Object> contextualStep;
        private final List<StepInterceptor> interceptors;

        @SuppressWarnings("unchecked")
        public StepPlanNode(Flow.StepInfo info, Step<?, ?> step, Step.Contextual<?, ?> contextualStep, List<StepInterceptor> interceptors) {
            this.id = info.id();
            this.path = info.path();
            this.address = info.address();
            this.contextual = info.isContextual();
            this.step = (Step<Object, Object>) step;
            this.contextualStep = (Step.Contextual<Object, Object>) contextualStep;
            this.interceptors = interceptors != null ? interceptors : Collections.emptyList();
        }

        @Override public String id() { return id; }
        @Override public String path() { return path; }
        @Override public String address() { return address; }
        @Override public NodeKind kind() { return NodeKind.STEP; }
        public boolean isContextual() { return contextual; }
        public Step<Object, Object> step() { return step; }
        public Step.Contextual<Object, Object> contextualStep() { return contextualStep; }
        public List<StepInterceptor> interceptors() { return interceptors; }
    }

    final class TapPlanNode implements DurablePlanNode {
        private final String id;
        private final String path;
        private final String address;
        private final boolean contextual;
        private final Action<Object> action;
        private final Action.Contextual<Object> contextualAction;
        private final List<StepInterceptor> interceptors;

        @SuppressWarnings("unchecked")
        public TapPlanNode(Flow.TapInfo info, Action<?> action, Action.Contextual<?> contextualAction, List<StepInterceptor> interceptors) {
            this.id = info.id();
            this.path = info.path();
            this.address = info.address();
            this.contextual = info.isContextual();
            this.action = (Action<Object>) action;
            this.contextualAction = (Action.Contextual<Object>) contextualAction;
            this.interceptors = interceptors != null ? interceptors : Collections.emptyList();
        }

        @Override public String id() { return id; }
        @Override public String path() { return path; }
        @Override public String address() { return address; }
        @Override public NodeKind kind() { return NodeKind.TAP; }
        public boolean isContextual() { return contextual; }
        public Action<Object> action() { return action; }
        public Action.Contextual<Object> contextualAction() { return contextualAction; }
        public List<StepInterceptor> interceptors() { return interceptors; }
    }

    final class GuardPlanNode implements DurablePlanNode {
        private final String id;
        private final String path;
        private final String address;
        private final Condition<Object> condition;
        private final Function<Object, StopReason> reasonFactory;

        @SuppressWarnings("unchecked")
        public GuardPlanNode(Flow.GuardInfo info, Condition<?> condition, Function<?, StopReason> reasonFactory) {
            this.id = info.id();
            this.path = info.path();
            this.address = info.address();
            this.condition = (Condition<Object>) condition;
            this.reasonFactory = (Function<Object, StopReason>) reasonFactory;
        }

        @Override public String id() { return id; }
        @Override public String path() { return path; }
        @Override public String address() { return address; }
        @Override public NodeKind kind() { return NodeKind.GUARD; }
        public Condition<Object> condition() { return condition; }
        public Function<Object, StopReason> reasonFactory() { return reasonFactory; }
    }

    final class ChoosePlanNode implements DurablePlanNode {
        private final String id;
        private final String path;
        private final String address;
        private final Function<Object, Object> selector;
        private final Map<Object, DurablePlanNode> branches;
        private final DurablePlanNode otherwiseBranch;
        private final Function<Object, StopReason> otherwiseStopReason;

        @SuppressWarnings("unchecked")
        public ChoosePlanNode(Flow.ChooseInfo<?> info, Function<?, ?> selector,
                              Map<?, DurablePlanNode> branches, DurablePlanNode otherwiseBranch,
                              Function<?, StopReason> otherwiseStopReason) {
            this.id = info.id();
            this.path = info.path();
            this.address = info.address();
            this.selector = (Function<Object, Object>) selector;
            this.branches = branches != null ? new LinkedHashMap<>(branches) : Collections.emptyMap();
            this.otherwiseBranch = otherwiseBranch;
            this.otherwiseStopReason = (Function<Object, StopReason>) otherwiseStopReason;
        }

        @Override public String id() { return id; }
        @Override public String path() { return path; }
        @Override public String address() { return address; }
        @Override public NodeKind kind() { return NodeKind.CHOOSE; }
        public Function<Object, Object> selector() { return selector; }
        public Map<Object, DurablePlanNode> branches() { return branches; }
        public DurablePlanNode otherwiseBranch() { return otherwiseBranch; }
        public Function<Object, StopReason> otherwiseStopReason() { return otherwiseStopReason; }
    }

    final class SubflowPlanNode implements DurablePlanNode {
        private final String id;
        private final String path;
        private final String address;
        private final String subflowId;
        private final DurablePlanNode subflowPlan;

        public SubflowPlanNode(Flow.SubflowInfo info, DurablePlanNode subflowPlan) {
            this.id = info.id();
            this.path = info.path();
            this.address = info.address();
            this.subflowId = info.subflowId();
            this.subflowPlan = subflowPlan;
        }

        @Override public String id() { return id; }
        @Override public String path() { return path; }
        @Override public String address() { return address; }
        @Override public NodeKind kind() { return NodeKind.SUBFLOW; }
        public String subflowId() { return subflowId; }
        public DurablePlanNode subflowPlan() { return subflowPlan; }
    }

    final class RecoverPlanNode implements DurablePlanNode {
        private final String id;
        private final String path;
        private final String address;
        private final boolean contextual;
        private final Recovery<Object, Object> recovery;
        private final Recovery.Contextual<Object, Object> contextualRecovery;

        @SuppressWarnings("unchecked")
        public RecoverPlanNode(Flow.RecoverInfo info, Recovery<?, ?> recovery, Recovery.Contextual<?, ?> contextualRecovery) {
            this.id = info.id();
            this.path = info.path();
            this.address = info.address();
            this.contextual = info.isContextual();
            this.recovery = (Recovery<Object, Object>) recovery;
            this.contextualRecovery = (Recovery.Contextual<Object, Object>) contextualRecovery;
        }

        @Override public String id() { return id; }
        @Override public String path() { return path; }
        @Override public String address() { return address; }
        @Override public NodeKind kind() { return NodeKind.RECOVER; }
        public boolean isContextual() { return contextual; }
        public Recovery<Object, Object> recovery() { return recovery; }
        public Recovery.Contextual<Object, Object> contextualRecovery() { return contextualRecovery; }
    }

    final class EnsurePlanNode implements DurablePlanNode {
        private final String id;
        private final String path;
        private final String address;
        private final boolean contextual;
        private final CompletionAction<Object, Object> completionAction;
        private final CompletionAction.Contextual<Object, Object> contextualCompletionAction;

        @SuppressWarnings("unchecked")
        public EnsurePlanNode(Flow.EnsureInfo info, CompletionAction<?, ?> completionAction, CompletionAction.Contextual<?, ?> contextualCompletionAction) {
            this.id = info.id();
            this.path = info.path();
            this.address = info.address();
            this.contextual = info.isContextual();
            this.completionAction = (CompletionAction<Object, Object>) completionAction;
            this.contextualCompletionAction = (CompletionAction.Contextual<Object, Object>) contextualCompletionAction;
        }

        @Override public String id() { return id; }
        @Override public String path() { return path; }
        @Override public String address() { return address; }
        @Override public NodeKind kind() { return NodeKind.ENSURE; }
        public boolean isContextual() { return contextual; }
        public CompletionAction<Object, Object> completionAction() { return completionAction; }
        public CompletionAction.Contextual<Object, Object> contextualCompletionAction() { return contextualCompletionAction; }
    }

    final class SequencePlanNode implements DurablePlanNode {
        private final String id;
        private final String path;
        private final String address;
        private final List<DurablePlanNode> children;
        private RecoverPlanNode recoverNode;
        private EnsurePlanNode ensureNode;

        public SequencePlanNode(Flow.SequenceInfo info, List<DurablePlanNode> children) {
            this.id = info.id();
            this.path = info.path();
            this.address = info.address();
            this.children = children != null ? children : Collections.emptyList();
        }

        @Override public String id() { return id; }
        @Override public String path() { return path; }
        @Override public String address() { return address; }
        @Override public NodeKind kind() { return NodeKind.SEQUENCE; }
        public List<DurablePlanNode> children() { return children; }
        public RecoverPlanNode recoverNode() { return recoverNode; }
        public EnsurePlanNode ensureNode() { return ensureNode; }

        public void setRecoverNode(RecoverPlanNode recoverNode) { this.recoverNode = recoverNode; }
        public void setEnsureNode(EnsurePlanNode ensureNode) { this.ensureNode = ensureNode; }
    }
}
