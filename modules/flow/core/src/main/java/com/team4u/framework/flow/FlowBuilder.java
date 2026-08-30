package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 流程流式构造器。每次操作均产生新的不可变构造器状态，前后步骤的值类型由编译器推导保证。
 *
 * @param <I> 流程入口输入类型
 * @param <C> 当前步骤值类型
 * @author jay.wu
 */
public final class FlowBuilder<I, C> {

    private final String flowId;
    private final List<NodeSpec> nodeSpecs;
    private final List<StepInterceptor> scopedInterceptors;
    private final RecoverSpec recoverSpec;
    private final EnsureSpec ensureSpec;

    FlowBuilder(String flowId) {
        this(validateFlowId(flowId), Collections.emptyList(), Collections.emptyList(), null, null);
    }

    private FlowBuilder(String flowId,
                        List<NodeSpec> nodeSpecs,
                        List<StepInterceptor> scopedInterceptors,
                        RecoverSpec recoverSpec,
                        EnsureSpec ensureSpec) {
        this.flowId = flowId;
        this.nodeSpecs = nodeSpecs;
        this.scopedInterceptors = scopedInterceptors;
        this.recoverSpec = recoverSpec;
        this.ensureSpec = ensureSpec;
    }

    private static String validateFlowId(String flowId) {
        if (flowId == null || flowId.trim().isEmpty()) {
            throw new IllegalArgumentException("flowId must not be null or blank");
        }
        return flowId;
    }

    private static String validateNodeId(String nodeId) {
        if (nodeId == null || nodeId.trim().isEmpty()) {
            throw new IllegalArgumentException("nodeId must not be null or blank");
        }
        return nodeId;
    }

    /**
     * 为当前作用域直接声明的 Step 和 Tap 添加拦截器。
     *
     * @param interceptor 拦截器，非 null
     * @return 新 FlowBuilder
     */
    public FlowBuilder<I, C> interceptor(StepInterceptor interceptor) {
        if (interceptor == null) {
            throw new IllegalArgumentException("StepInterceptor must not be null");
        }
        List<StepInterceptor> newScoped = new ArrayList<>(this.scopedInterceptors);
        newScoped.add(interceptor);
        return new FlowBuilder<>(flowId, nodeSpecs, newScoped, recoverSpec, ensureSpec);
    }

    /**
     * 添加普通业务转换步骤。
     *
     * @param id           节点 ID，非 null
     * @param step         业务步骤函数，非 null
     * @param interceptors 可选节点级拦截器
     * @param <O>          步骤输出类型
     * @return 新 FlowBuilder
     */
    public <O> FlowBuilder<I, O> step(String id, Step<C, O> step, StepInterceptor... interceptors) {
        validateNodeId(id);
        if (step == null) {
            throw new IllegalArgumentException("Step must not be null for node [" + id + "]");
        }
        List<StepInterceptor> nodeInterceptors = interceptors != null && interceptors.length > 0
                ? Arrays.asList(interceptors) : Collections.emptyList();
        for (StepInterceptor interceptor : nodeInterceptors) {
            if (interceptor == null) {
                throw new IllegalArgumentException("StepInterceptor must not be null for node [" + id + "]");
            }
        }
        List<NodeSpec> newSpecs = new ArrayList<>(nodeSpecs);
        newSpecs.add(new StepSpec(id, step, null, nodeInterceptors));
        return new FlowBuilder<>(flowId, newSpecs, scopedInterceptors, recoverSpec, ensureSpec);
    }

    /**
     * 添加上下文型业务转换步骤。
     *
     * @param id             节点 ID，非 null
     * @param contextualStep 上下文型业务步骤函数，非 null
     * @param interceptors   可选节点级拦截器
     * @param <O>            步骤输出类型
     * @return 新 FlowBuilder
     */
    public <O> FlowBuilder<I, O> step(String id, Step.Contextual<C, O> contextualStep, StepInterceptor... interceptors) {
        validateNodeId(id);
        if (contextualStep == null) {
            throw new IllegalArgumentException("Step.Contextual must not be null for node [" + id + "]");
        }
        List<StepInterceptor> nodeInterceptors = interceptors != null && interceptors.length > 0
                ? Arrays.asList(interceptors) : Collections.emptyList();
        for (StepInterceptor interceptor : nodeInterceptors) {
            if (interceptor == null) {
                throw new IllegalArgumentException("StepInterceptor must not be null for node [" + id + "]");
            }
        }
        List<NodeSpec> newSpecs = new ArrayList<>(nodeSpecs);
        newSpecs.add(new StepSpec(id, null, contextualStep, nodeInterceptors));
        return new FlowBuilder<>(flowId, newSpecs, scopedInterceptors, recoverSpec, ensureSpec);
    }

    /**
     * 添加普通副作用动作（原样透传当前值）。
     *
     * @param id           节点 ID，非 null
     * @param action       副作用动作，非 null
     * @param interceptors 可选节点级拦截器
     * @return 新 FlowBuilder
     */
    public FlowBuilder<I, C> tap(String id, Action<C> action, StepInterceptor... interceptors) {
        validateNodeId(id);
        if (action == null) {
            throw new IllegalArgumentException("Action must not be null for node [" + id + "]");
        }
        List<StepInterceptor> nodeInterceptors = interceptors != null && interceptors.length > 0
                ? Arrays.asList(interceptors) : Collections.emptyList();
        for (StepInterceptor interceptor : nodeInterceptors) {
            if (interceptor == null) {
                throw new IllegalArgumentException("StepInterceptor must not be null for node [" + id + "]");
            }
        }
        List<NodeSpec> newSpecs = new ArrayList<>(nodeSpecs);
        newSpecs.add(new TapSpec(id, action, null, nodeInterceptors));
        return new FlowBuilder<>(flowId, newSpecs, scopedInterceptors, recoverSpec, ensureSpec);
    }

    /**
     * 添加上下文型副作用动作（原样透传当前值）。
     *
     * @param id               节点 ID，非 null
     * @param contextualAction 上下文型副作用动作，非 null
     * @param interceptors     可选节点级拦截器
     * @return 新 FlowBuilder
     */
    public FlowBuilder<I, C> tap(String id, Action.Contextual<C> contextualAction, StepInterceptor... interceptors) {
        validateNodeId(id);
        if (contextualAction == null) {
            throw new IllegalArgumentException("Action.Contextual must not be null for node [" + id + "]");
        }
        List<StepInterceptor> nodeInterceptors = interceptors != null && interceptors.length > 0
                ? Arrays.asList(interceptors) : Collections.emptyList();
        for (StepInterceptor interceptor : nodeInterceptors) {
            if (interceptor == null) {
                throw new IllegalArgumentException("StepInterceptor must not be null for node [" + id + "]");
            }
        }
        List<NodeSpec> newSpecs = new ArrayList<>(nodeSpecs);
        newSpecs.add(new TapSpec(id, null, contextualAction, nodeInterceptors));
        return new FlowBuilder<>(flowId, newSpecs, scopedInterceptors, recoverSpec, ensureSpec);
    }

    /**
     * 添加守卫条件：条件不满足时惰性生成原因并产生业务 STOPPED。
     *
     * @param id            节点 ID，非 null
     * @param condition     守卫条件，非 null
     * @param reasonFactory 停止原因生成函数，非 null
     * @return 新 FlowBuilder
     */
    public FlowBuilder<I, C> guard(String id, Condition<C> condition, Function<C, StopReason> reasonFactory) {
        validateNodeId(id);
        if (condition == null) {
            throw new IllegalArgumentException("Condition must not be null for node [" + id + "]");
        }
        if (reasonFactory == null) {
            throw new IllegalArgumentException("reasonFactory must not be null for node [" + id + "]");
        }
        List<NodeSpec> newSpecs = new ArrayList<>(nodeSpecs);
        newSpecs.add(new GuardSpec(id, condition, reasonFactory));
        return new FlowBuilder<>(flowId, newSpecs, scopedInterceptors, recoverSpec, ensureSpec);
    }

    /**
     * 开启分支选择定义。
     *
     * @param id       分支节点 ID，非 null
     * @param selector key 选择函数，非 null
     * @param <K>      key 类型
     * @return 分支起始构造器
     */
    public <K> ChooseStart<I, C, K> choose(String id, Function<C, K> selector) {
        validateNodeId(id);
        if (selector == null) {
            throw new IllegalArgumentException("Choose selector must not be null for node [" + id + "]");
        }
        return new ChooseStart<>(this, id, selector);
    }

    <K, O> FlowBuilder<I, O> addChoose(String id, Function<C, K> selector,
                                       Map<K, Flow<C, O>> branches,
                                       Flow<C, O> otherwiseBranch,
                                       Function<C, StopReason> otherwiseStopReason) {
        if (branches == null || branches.isEmpty()) {
            throw new IllegalArgumentException("Choose node [" + id + "] must declare at least one branch");
        }
        List<NodeSpec> newSpecs = new ArrayList<>(nodeSpecs);
        newSpecs.add(new ChooseSpec(id, selector, branches, otherwiseBranch, otherwiseStopReason));
        return new FlowBuilder<>(flowId, newSpecs, scopedInterceptors, recoverSpec, ensureSpec);
    }

    /**
     * 组合另一个 Flow 作为子流程执行并保留轨迹层级。
     *
     * @param subflow 子流程定义，非 null
     * @param <O>     子流程输出类型
     * @return 新 FlowBuilder
     */
    public <O> FlowBuilder<I, O> then(Flow<C, O> subflow) {
        if (subflow == null) {
            throw new IllegalArgumentException("Subflow must not be null");
        }
        List<NodeSpec> newSpecs = new ArrayList<>(nodeSpecs);
        newSpecs.add(new SubflowSpec(subflow.id(), subflow));
        return new FlowBuilder<>(flowId, newSpecs, scopedInterceptors, recoverSpec, ensureSpec);
    }

    /**
     * 声明失败恢复：当此前步骤出现技术 FAILED 时调用。
     *
     * @param id       恢复节点 ID，非 null
     * @param recovery 恢复逻辑，非 null
     * @return 恢复后构造器
     */
    public RecoveredFlowBuilder<I, C> recover(String id, Recovery<I, C> recovery) {
        validateNodeId(id);
        if (recovery == null) {
            throw new IllegalArgumentException("Recovery must not be null for node [" + id + "]");
        }
        if (recoverSpec != null) {
            throw new IllegalStateException("recover already declared for flow [" + flowId + "]");
        }
        if (ensureSpec != null) {
            throw new IllegalStateException("cannot declare recover after ensure in flow [" + flowId + "]");
        }
        RecoverSpec rec = new RecoverSpec(id, recovery, null);
        return new RecoveredFlowBuilder<>(new FlowBuilder<>(flowId, nodeSpecs, scopedInterceptors, rec, ensureSpec));
    }

    /**
     * 声明上下文型失败恢复。
     *
     * @param id       恢复节点 ID，非 null
     * @param recovery 上下文型恢复逻辑，非 null
     * @return 恢复后构造器
     */
    public RecoveredFlowBuilder<I, C> recover(String id, Recovery.Contextual<I, C> recovery) {
        validateNodeId(id);
        if (recovery == null) {
            throw new IllegalArgumentException("Recovery.Contextual must not be null for node [" + id + "]");
        }
        if (recoverSpec != null) {
            throw new IllegalStateException("recover already declared for flow [" + flowId + "]");
        }
        if (ensureSpec != null) {
            throw new IllegalStateException("cannot declare recover after ensure in flow [" + flowId + "]");
        }
        RecoverSpec rec = new RecoverSpec(id, null, recovery);
        return new RecoveredFlowBuilder<>(new FlowBuilder<>(flowId, nodeSpecs, scopedInterceptors, rec, ensureSpec));
    }

    /**
     * 声明终态清理动作（无论成功、停止或失败均执行一次）。
     *
     * @param id               节点 ID，非 null
     * @param completionAction 清理动作，非 null
     * @return 终态构造器
     */
    public EnsuredFlowBuilder<I, C> ensure(String id, CompletionAction<I, C> completionAction) {
        validateNodeId(id);
        if (completionAction == null) {
            throw new IllegalArgumentException("CompletionAction must not be null for node [" + id + "]");
        }
        if (ensureSpec != null) {
            throw new IllegalStateException("ensure already declared for flow [" + flowId + "]");
        }
        EnsureSpec ens = new EnsureSpec(id, completionAction, null);
        return new EnsuredFlowBuilder<>(new FlowBuilder<>(flowId, nodeSpecs, scopedInterceptors, recoverSpec, ens));
    }

    /**
     * 声明上下文型终态清理动作。
     *
     * @param id               节点 ID，非 null
     * @param completionAction 上下文型清理动作，非 null
     * @return 终态构造器
     */
    public EnsuredFlowBuilder<I, C> ensure(String id, CompletionAction.Contextual<I, C> completionAction) {
        validateNodeId(id);
        if (completionAction == null) {
            throw new IllegalArgumentException("CompletionAction.Contextual must not be null for node [" + id + "]");
        }
        if (ensureSpec != null) {
            throw new IllegalStateException("ensure already declared for flow [" + flowId + "]");
        }
        EnsureSpec ens = new EnsureSpec(id, null, completionAction);
        return new EnsuredFlowBuilder<>(new FlowBuilder<>(flowId, nodeSpecs, scopedInterceptors, recoverSpec, ens));
    }

    /**
     * 校验定义并生成不可变 Flow 实例。
     *
     * @return 流程定义
     */
    public Flow<I, C> build() {
        if (nodeSpecs.isEmpty()) {
            throw new IllegalStateException("Flow sequence must contain at least one node in flow [" + flowId + "]");
        }

        // Validate unique node IDs in current scope
        Set<String> nodeIds = new HashSet<>();
        for (NodeSpec spec : nodeSpecs) {
            if (!nodeIds.add(spec.id())) {
                throw new IllegalArgumentException("Duplicate node ID [" + spec.id() + "] in flow [" + flowId + "]");
            }
        }
        if (recoverSpec != null && !nodeIds.add(recoverSpec.id())) {
            throw new IllegalArgumentException("Duplicate node ID [" + recoverSpec.id() + "] for recover in flow [" + flowId + "]");
        }
        if (ensureSpec != null && !nodeIds.add(ensureSpec.id())) {
            throw new IllegalArgumentException("Duplicate node ID [" + ensureSpec.id() + "] for ensure in flow [" + flowId + "]");
        }

        List<FlowNode> builtNodes = new ArrayList<>(nodeSpecs.size());
        for (int i = 0; i < nodeSpecs.size(); i++) {
            NodeSpec spec = nodeSpecs.get(i);
            String address = "/s" + i + ":" + spec.id();
            String path = spec.id();
            builtNodes.add(spec.build(address, path, scopedInterceptors));
        }

        RecoverNode builtRecover = null;
        if (recoverSpec != null) {
            builtRecover = new RecoverNode(recoverSpec.id(), recoverSpec.id(), "/recover:" + recoverSpec.id(),
                    recoverSpec.recovery(), recoverSpec.contextualRecovery());
        }

        EnsureNode builtEnsure = null;
        if (ensureSpec != null) {
            builtEnsure = new EnsureNode(ensureSpec.id(), ensureSpec.id(), "/ensure:" + ensureSpec.id(),
                    ensureSpec.completionAction(), ensureSpec.contextualCompletionAction());
        }

        SequenceNode rootNode = new SequenceNode(flowId, flowId, "/", builtNodes, builtRecover, builtEnsure);
        return new DefaultFlow<>(flowId, rootNode);
    }

    // Node specs
    interface NodeSpec {
        String id();
        FlowNode build(String address, String path, List<StepInterceptor> scopedInterceptors);
    }

    private static final class StepSpec implements NodeSpec {
        private final String id;
        private final Step<?, ?> step;
        private final Step.Contextual<?, ?> contextualStep;
        private final List<StepInterceptor> nodeInterceptors;

        StepSpec(String id, Step<?, ?> step, Step.Contextual<?, ?> contextualStep, List<StepInterceptor> nodeInterceptors) {
            this.id = id;
            this.step = step;
            this.contextualStep = contextualStep;
            this.nodeInterceptors = nodeInterceptors;
        }

        @Override
        public String id() { return id; }

        @Override
        public FlowNode build(String address, String path, List<StepInterceptor> scopedInterceptors) {
            List<StepInterceptor> effective = new ArrayList<>(scopedInterceptors.size() + nodeInterceptors.size());
            effective.addAll(scopedInterceptors);
            effective.addAll(nodeInterceptors);
            return new StepNode(id, path, address, step, contextualStep, effective);
        }
    }

    private static final class TapSpec implements NodeSpec {
        private final String id;
        private final Action<?> action;
        private final Action.Contextual<?> contextualAction;
        private final List<StepInterceptor> nodeInterceptors;

        TapSpec(String id, Action<?> action, Action.Contextual<?> contextualAction, List<StepInterceptor> nodeInterceptors) {
            this.id = id;
            this.action = action;
            this.contextualAction = contextualAction;
            this.nodeInterceptors = nodeInterceptors;
        }

        @Override
        public String id() { return id; }

        @Override
        public FlowNode build(String address, String path, List<StepInterceptor> scopedInterceptors) {
            List<StepInterceptor> effective = new ArrayList<>(scopedInterceptors.size() + nodeInterceptors.size());
            effective.addAll(scopedInterceptors);
            effective.addAll(nodeInterceptors);
            return new TapNode(id, path, address, action, contextualAction, effective);
        }
    }

    private static final class GuardSpec implements NodeSpec {
        private final String id;
        private final Condition<?> condition;
        private final Function<?, StopReason> reasonFactory;

        GuardSpec(String id, Condition<?> condition, Function<?, StopReason> reasonFactory) {
            this.id = id;
            this.condition = condition;
            this.reasonFactory = reasonFactory;
        }

        @Override
        public String id() { return id; }

        @Override
        public FlowNode build(String address, String path, List<StepInterceptor> scopedInterceptors) {
            return new GuardNode(id, path, address, condition, reasonFactory);
        }
    }

    private static final class ChooseSpec implements NodeSpec {
        private final String id;
        private final Function<?, ?> selector;
        private final Map<?, ? extends Flow<?, ?>> branches;
        private final Flow<?, ?> otherwiseBranch;
        private final Function<?, StopReason> otherwiseStopReason;

        ChooseSpec(String id, Function<?, ?> selector, Map<?, ? extends Flow<?, ?>> branches,
                   Flow<?, ?> otherwiseBranch, Function<?, StopReason> otherwiseStopReason) {
            this.id = id;
            this.selector = selector;
            this.branches = branches;
            this.otherwiseBranch = otherwiseBranch;
            this.otherwiseStopReason = otherwiseStopReason;
        }

        @Override
        public String id() { return id; }

        @Override
        public FlowNode build(String address, String path, List<StepInterceptor> scopedInterceptors) {
            return new ChooseNode(id, path, address, selector, branches, otherwiseBranch, otherwiseStopReason);
        }
    }

    private static final class SubflowSpec implements NodeSpec {
        private final String id;
        private final Flow<?, ?> subflow;

        SubflowSpec(String id, Flow<?, ?> subflow) {
            this.id = id;
            this.subflow = subflow;
        }

        @Override
        public String id() { return id; }

        @Override
        public FlowNode build(String address, String path, List<StepInterceptor> scopedInterceptors) {
            return new SubflowNode(id, path, address, subflow);
        }
    }

    private static final class RecoverSpec {
        private final String id;
        private final Recovery<?, ?> recovery;
        private final Recovery.Contextual<?, ?> contextualRecovery;

        RecoverSpec(String id, Recovery<?, ?> recovery, Recovery.Contextual<?, ?> contextualRecovery) {
            this.id = id;
            this.recovery = recovery;
            this.contextualRecovery = contextualRecovery;
        }

        String id() { return id; }
        Recovery<?, ?> recovery() { return recovery; }
        Recovery.Contextual<?, ?> contextualRecovery() { return contextualRecovery; }
    }

    private static final class EnsureSpec {
        private final String id;
        private final CompletionAction<?, ?> completionAction;
        private final CompletionAction.Contextual<?, ?> contextualCompletionAction;

        EnsureSpec(String id, CompletionAction<?, ?> completionAction, CompletionAction.Contextual<?, ?> contextualCompletionAction) {
            this.id = id;
            this.completionAction = completionAction;
            this.contextualCompletionAction = contextualCompletionAction;
        }

        String id() { return id; }
        CompletionAction<?, ?> completionAction() { return completionAction; }
        CompletionAction.Contextual<?, ?> contextualCompletionAction() { return contextualCompletionAction; }
    }
}
