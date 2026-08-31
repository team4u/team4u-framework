package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.ControlKind;
import com.team4u.framework.flow.ExecutableBinding;
import com.team4u.framework.flow.ExecutableFlowVisitor;
import com.team4u.framework.flow.ExecutableParallelBranch;
import com.team4u.framework.flow.ExecutableRouteCase;
import com.team4u.framework.flow.FallbackTrigger;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.JoinStrategy;
import com.team4u.framework.flow.NodeDescriptor;
import com.team4u.framework.flow.OperationResolver;
import com.team4u.framework.flow.Outcome;
import com.team4u.framework.flow.ResumePoint;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 耐久化物理执行树编译器（Durable Plan Compiler）。
 *
 * <p>实现 Core 公开的投影 SPI {@link ExecutableFlowVisitor}，将已校验的拓扑计划投影为 Durable 专属物理树 {@link DurablePlanNode}，
 * 并预计算生成所有插槽角色列表（{@code slotRoles}）、挂起点映射表（{@code resumePoints}）以及线程池需求特征。</p>
 *
 * @author jay.wu
 */
final class DurablePlanCompiler implements ExecutableFlowVisitor<DurablePlanNode> {

    /**
     * 耐久化流程定义元数据密封容器。
     */
    @Getter
    @Accessors(fluent = true)
    static final class Definition {
        private final DurablePlanNode root;
        private final Map<String, DurablePlanNode> byPath;
        private final Set<String> slotRoles;
        private final Map<String, ResumePoint<?>> resumePoints;
        private final boolean requiresExecutor;

        Definition(DurablePlanNode root, Map<String, DurablePlanNode> byPath,
                   Set<String> slotRoles, Map<String, ResumePoint<?>> resumePoints,
                   boolean requiresExecutor) {
            this.root = Objects.requireNonNull(root, "root must not be null");
            this.byPath = Collections.unmodifiableMap(
                    new LinkedHashMap<String, DurablePlanNode>(byPath));
            this.slotRoles = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(slotRoles));
            this.resumePoints = Collections.unmodifiableMap(
                    new LinkedHashMap<String, ResumePoint<?>>(resumePoints));
            this.requiresExecutor = requiresExecutor;
        }
    }

    private final LinkedHashMap<String, DurablePlanNode> byPath =
            new LinkedHashMap<String, DurablePlanNode>();
    private final LinkedHashSet<String> slotRoles = new LinkedHashSet<String>();
    private final LinkedHashMap<String, ResumePoint<?>> resumePoints =
            new LinkedHashMap<String, ResumePoint<?>>();
    private boolean requiresExecutor;

    private DurablePlanCompiler() {
        slotRoles.add("input");
    }

    /**
     * 编译 Flow 为 Durable 执行定义。
     *
     * @param flow     逻辑流定义
     * @param resolver 组件解析器
     * @return DurablePlanCompiler.Definition
     */
    static Definition compile(Flow<?, ?> flow, OperationResolver resolver) {

        Objects.requireNonNull(flow, "flow must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        DurablePlanCompiler compiler = new DurablePlanCompiler();
        DurablePlanNode root = flow.project(resolver, compiler);
        if (compiler.byPath.get(root.descriptor().path()) != root) {
            throw invalid("Projected root is not registered at its path");
        }
        return new Definition(root, compiler.byPath, compiler.slotRoles,
                compiler.resumePoints, compiler.requiresExecutor);
    }

    @Override
    public DurablePlanNode visitInvoke(NodeDescriptor descriptor,
                                       ExecutableBinding binding,
                                       Function<Object, Object> project,
                                       BiFunction<Object, Object, Object> merge) {
        DurablePlanNode.Invoke node = new DurablePlanNode.Invoke(
                descriptor, binding, project, merge);
        register(node);
        slotRoles.add(nodeRole(descriptor.path()));
        return node;
    }

    @Override
    public DurablePlanNode visitSequence(NodeDescriptor descriptor,
                                         List<DurablePlanNode> children,
                                         Optional<String> scopeName) {
        return register(new DurablePlanNode.Sequence(
                descriptor, children, scopeName));
    }

    @Override
    public DurablePlanNode visitRoute(NodeDescriptor descriptor,
                                      ExecutableBinding selectorBinding,
                                      List<ExecutableRouteCase<DurablePlanNode>> cases,
                                      Optional<DurablePlanNode> otherwise) {
        final String selectorPath = descriptor.path() + "/selector";
        NodeDescriptor selectorDescriptor = new NodeDescriptor(selectorPath,
                Optional.<String>empty(), NodeDescriptor.Kind.INVOKE,
                Optional.of(selectorBinding.contractClass()),
                Optional.of(selectorBinding.implementationClass()),
                selectorBinding.qualifier());
        DurablePlanNode.Invoke selector = new DurablePlanNode.Invoke(
                selectorDescriptor, selectorBinding,
                new Function<Object, Object>() {
                    @Override public Object apply(Object value) { return value; }
                },
                new BiFunction<Object, Object, Object>() {
                    @Override public Object apply(Object ignored, Object value) { return value; }
                });
        register(selector);
        slotRoles.add(nodeRole(selectorPath));
        ArrayList<DurablePlanNode.Route.RouteCase> projected =
                new ArrayList<DurablePlanNode.Route.RouteCase>(cases.size());
        for (ExecutableRouteCase<DurablePlanNode> candidate : cases) {
            projected.add(new DurablePlanNode.Route.RouteCase(
                    candidate.key(), candidate.branch()));
        }
        return register(new DurablePlanNode.Route(descriptor, selector, projected,
                otherwise.orElse(null)));
    }

    @Override
    public DurablePlanNode visitFallback(NodeDescriptor descriptor,
                                         FallbackTrigger trigger,
                                         List<DurablePlanNode> branches) {
        return register(new DurablePlanNode.Fallback(
                descriptor, trigger, branches));
    }

    @Override
    public DurablePlanNode visitParallel(NodeDescriptor descriptor,
                                         List<ExecutableParallelBranch<DurablePlanNode>> branches,
                                         JoinStrategy<?> join) {
        ArrayList<DurablePlanNode.Parallel.ParallelBranch> projected =
                new ArrayList<DurablePlanNode.Parallel.ParallelBranch>(branches.size());
        for (ExecutableParallelBranch<DurablePlanNode> branch : branches) {
            projected.add(new DurablePlanNode.Parallel.ParallelBranch(
                    branch.token(), branch.branchPlan()));
        }
        slotRoles.add(nodeRole(descriptor.path()));
        return register(new DurablePlanNode.Parallel(descriptor, projected, join));
    }

    @Override
    public DurablePlanNode visitAwait(NodeDescriptor descriptor,
                                      ResumePoint<?> resumePoint) {
        ResumePoint<?> previous = resumePoints.put(resumePoint.name(), resumePoint);
        if (previous != null) {
            throw invalid("Duplicate ResumePoint: " + resumePoint.name());
        }
        slotRoles.add(resumeRole(resumePoint.name()));
        return register(new DurablePlanNode.Await(descriptor, resumePoint));
    }

    @Override
    public DurablePlanNode visitControl(NodeDescriptor descriptor, ControlKind kind,
                                        DurablePlanNode body,
                                        Optional<ExecutableBinding> binding,
                                        Function<Object, Object> keyProjection,
                                        Object configuration) {
        if (kind == ControlKind.POLICY || kind == ControlKind.PERSISTENT_POLICY) {
            slotRoles.add(keyRole(descriptor.path()));
        }
        if (kind == ControlKind.PERSISTENT_POLICY) {
            slotRoles.add(policyRole(descriptor.path()));
        }
        if (kind == ControlKind.TIMEOUT) requiresExecutor = true;
        return register(new DurablePlanNode.Control(descriptor, kind, body,
                binding, keyProjection, configuration));
    }

    @Override
    public DurablePlanNode visitComplete(NodeDescriptor descriptor,
                                         Outcome<?> outcome, boolean identity) {
        if (!identity && outcome instanceof Outcome.Accepted) {
            slotRoles.add(nodeRole(descriptor.path()));
        }
        return register(new DurablePlanNode.Complete(descriptor, outcome, identity));
    }

    private <T extends DurablePlanNode> T register(T node) {
        String path = node.descriptor().path();
        if (byPath.put(path, node) != null) {
            throw invalid("Duplicate projected node path: " + path);
        }
        return node;
    }

    static String nodeRole(String path) { return "node:" + path; }
    static String keyRole(String path) { return "key:" + path; }
    static String policyRole(String path) { return "policy:" + path; }
    static String resumeRole(String name) { return "resume:" + name; }

    private static DurableException invalid(String message) {
        return new DurableException(DurableException.Error.INVALID_DEFINITION, message);
    }
}
