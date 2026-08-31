package com.team4u.framework.flow.compiler;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.model.FlowBuildException;
import com.team4u.framework.flow.spi.NodeDescriptor;
import com.team4u.framework.flow.spi.OperationResolver;

/**
 * 流程静态编译器（AST Lowering & 拓扑校验器）。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>降级转换（Lowering）</b>：通过 {@link LogicalLowererRegistry} 将高层逻辑抽象语法树（{@link Logical}）降级编译为运行时密封的物理执行树（{@link PlanNode}）；</li>
 *   <li><b>依赖组件绑定（Resolution）</b>：结合 {@link OperationResolver} 将流程中声明的 Class/Qualifier 一次性解析并绑定为单例 Bean 实例；</li>
 *   <li><b>静态拓扑约束检查</b>：
 *     <ul>
 *       <li>具名 Scope 名称在整个流程内必须唯一；</li>
 *       <li>Parallel 分支名称（Token）在同一并行块内必须唯一；</li>
 *       <li>挂起点（{@link ResumePoint}）名称在整个流程内必须全局唯一；</li>
 *       <li>Parallel 并行分支内严禁包含 {@link ResumePoint}（Await）以及 {@link PersistentPolicy}；</li>
 *       <li>检测是否存在重复 Label、非法 Binding 类型等；</li>
 *     </ul>
 *   </li>
 *   <li><b>非递归算法保证</b>：采用显式双工作栈后序遍历（Post-order Traversal），无栈深度限制。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public final class Compiler implements LoweringContext {

    /**
     * 流程静态编译结果密封容器。
     *
     * <p>包含编译后的根执行节点、路径索引字典、挂起点映射表以及并发死锁防御特征标记。</p>
     */
    @Getter
    @Accessors(fluent = true)
    public static final class Compiled {
        private final PlanNode root;
        private final Map<String, PlanNode> byPath;
        private final Map<String, ResumePoint<?>> resumePoints;
        private final boolean hasNestedWorkerTasks;
        private final boolean requiresCompensatingWorker;

        /**
         * 构造编译产物容器。
         *
         * @param root         执行根节点
         * @param byPath       按节点路径索引的只读字典
         * @param resumePoints 挂起点只读字典
         */
        public Compiled(PlanNode root, Map<String, PlanNode> byPath,
                        Map<String, ResumePoint<?>> resumePoints) {
            this.root = root;
            this.byPath = Collections.unmodifiableMap(new LinkedHashMap<String, PlanNode>(byPath));
            this.resumePoints = Collections.unmodifiableMap(new LinkedHashMap<String, ResumePoint<?>>(resumePoints));
            boolean nested = false;
            for (PlanNode node : byPath.values()) {
                if (node instanceof PlanNode.Parallel) {
                    nested = true;
                    break;
                }
                if (node instanceof PlanNode.Control) {
                    PlanNode.Control control = (PlanNode.Control) node;
                    if (control.kind() == PlanNode.Control.Kind.TIMEOUT) {
                        nested = true;
                        break;
                    }
                }
            }
            this.hasNestedWorkerTasks = nested;

            boolean requiresCompensating = false;
            for (PlanNode node : byPath.values()) {
                if (node instanceof PlanNode.Parallel) {
                    PlanNode.Parallel parallel = (PlanNode.Parallel) node;
                    for (PlanNode.ParallelBranch branch : parallel.branches()) {
                        if (hasNestedWorkerBlocking(branch.plan())) {
                            requiresCompensating = true;
                            break;
                        }
                    }
                    if (requiresCompensating) {
                        break;
                    }
                }
            }
            this.requiresCompensatingWorker = requiresCompensating;
        }

        private static boolean hasNestedWorkerBlocking(PlanNode node) {
            ArrayDeque<PlanNode> stack = new ArrayDeque<PlanNode>();
            stack.add(node);
            while (!stack.isEmpty()) {
                PlanNode curr = stack.pop();
                if (curr instanceof PlanNode.Parallel) {
                    return true;
                }
                if (curr instanceof PlanNode.Control) {
                    PlanNode.Control control = (PlanNode.Control) curr;
                    if (control.kind() == PlanNode.Control.Kind.TIMEOUT) {
                        return true;
                    }
                    stack.push(control.body());
                } else if (curr instanceof PlanNode.Sequence) {
                    stack.addAll(((PlanNode.Sequence) curr).children());
                } else if (curr instanceof PlanNode.Route) {
                    PlanNode.Route route = (PlanNode.Route) curr;
                    for (PlanNode.Route.RouteCase rc : route.cases()) {
                        stack.push(rc.branch());
                    }
                    if (route.otherwise() != null) {
                        stack.push(route.otherwise());
                    }
                } else if (curr instanceof PlanNode.Fallback) {
                    stack.addAll(((PlanNode.Fallback) curr).branches());
                }
            }
            return false;
        }
    }

    @AllArgsConstructor
    @EqualsAndHashCode
    private static final class BindingKey {
        private final Class<?> contract;
        private final String qualifier;
        private final Logical.BindingKind kind;
    }

    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    private static final class Resolved {
        private final Object instance;
        private final Class<?> implementation;
    }

    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    static final class Work {
        private final Logical logical;
        private final String path;
        private final String label;
        private final boolean parallel;
        private final boolean build;
    }

    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    static final class Child {
        private final Logical logical;
        private final String path;
        private final boolean parallel;
    }

    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    private static final class Normalized {
        private final Logical logical;
        private final String label;
    }

    private final OperationResolver resolver;
    private final List<FlowBuildException.Problem> problems = new ArrayList<FlowBuildException.Problem>();
    private final Map<BindingKey, Resolved> resolutions = new HashMap<BindingKey, Resolved>();
    private final Map<BindingKey, String> resolutionFailures = new HashMap<BindingKey, String>();
    private final LinkedHashMap<String, PlanNode> byPath = new LinkedHashMap<String, PlanNode>();
    private final LinkedHashMap<String, ResumePoint<?>> resumePoints = new LinkedHashMap<String, ResumePoint<?>>();
    private final Set<String> scopeNames = new HashSet<String>();
    /** 并行块分支名作用域栈：每个并行块独立校验块内唯一，不同块允许同名。 */
    private final ArrayDeque<Set<String>> parallelBranchScopes = new ArrayDeque<Set<String>>();

    private Compiler(OperationResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    /** 编译入口：下降 root 后若发现任何 Problem 则抛出 FlowBuildException。 */
    public static Compiled compile(Flow<?, ?> flow, OperationResolver resolver) {
        Objects.requireNonNull(flow, "flow must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        Compiler compiler = new Compiler(resolver);
        PlanNode root = compiler.lower(flow.root());
        if (!compiler.problems.isEmpty()) {
            throw new FlowBuildException(compiler.problems);
        }
        return new Compiled(root, compiler.byPath, compiler.resumePoints);
    }

    /**
     * 工作列表驱动的后序下降：对每个节点先记录一个 build 任务，再压入其子节点的 normalize 任务，
     * 保证子节点先于父节点完成 build。
     */
    private PlanNode lower(Logical root) {
        ArrayDeque<Work> work = new ArrayDeque<Work>();
        work.addLast(new Work(root, FlowPaths.root(), null, false, false));
        while (!work.isEmpty()) {
            Work current = work.removeLast();
            if (current.build()) {
                build(current);
                continue;
            }
            Normalized normalized = normalize(current.logical(), current.label(), current.path());
            Work ready = new Work(normalized.logical(), current.path(), normalized.label(),
                    current.parallel(), true);
            work.addLast(ready);
            List<Child> children = children(ready);
            for (int index = children.size() - 1; index >= 0; index--) {
                Child child = children.get(index);
                work.addLast(new Work(child.logical(), child.path(), null,
                        child.parallel(), false));
            }
        }
        return byPath.get(FlowPaths.root());
    }

    /** 展平 Logical.Named 包装；重复 label 记为 Problem。 */
    private Normalized normalize(Logical logical, String label, String path) {
        while (logical instanceof Logical.Named) {
            Logical.Named named = (Logical.Named) logical;
            if (label != null) {
                problem("DUPLICATE_LABEL", path, "Node has more than one label");
            }
            label = named.label();
            logical = named.body();
        }
        return new Normalized(logical, label);
    }

    /** 委托 Lowerer 策略返回某节点的直接子 Logical 及其路径。 */
    @SuppressWarnings("unchecked")
    private List<Child> children(Work work) {
        LogicalLowerer<Logical> lowerer = (LogicalLowerer<Logical>) LogicalLowererRegistry.global()
                .get(work.logical().getClass())
                .orElseThrow(() -> new IllegalStateException("Unknown logical node: " + work.logical().getClass()));
        return lowerer.children(work.logical(), work);
    }

    /** 委托 Lowerer 策略根据 Logical 类型构造对应的 PlanNode，并校验 scope/branch/ResumePoint 唯一性与并行约束。 */
    @SuppressWarnings("unchecked")
    private void build(Work work) {
        Logical logical = work.logical();
        LogicalLowerer<Logical> lowerer = (LogicalLowerer<Logical>) LogicalLowererRegistry.global()
                .get(logical.getClass())
                .orElseThrow(() -> new IllegalStateException("Unknown logical node: " + logical.getClass()));
        PlanNode node = lowerer.build(logical, work, this);
        if (byPath.put(work.path(), node) != null) {
            problem("DUPLICATE_PATH", work.path(), "Compiler generated a duplicate path");
        }
    }

    @Override
    public PlanNode.Invoke invoke(Logical.Invoke invoke, String path, String label) {
        PlanNode.BoundTarget target = resolve(invoke.binding(), path);
        NodeDescriptor descriptor = target == null
                ? NodeDescriptor.structural(path, label, NodeDescriptor.Kind.INVOKE)
                : new NodeDescriptor(path, Optional.ofNullable(label),
                NodeDescriptor.Kind.INVOKE, Optional.of(target.contract()),
                Optional.of(target.implementation()),
                Optional.ofNullable(target.qualifier()));
        return new PlanNode.Invoke(descriptor, target, invoke.project(), invoke.merge());
    }

    /**
     * 解析 Logical.Binding 为 PlanNode.BoundTarget：实例绑定直接采用，类绑定通过 resolver 查询。
     * 同一个 BindingKey 的解析结果与失败都会缓存，避免重复解析。
     */
    @Override
    public PlanNode.BoundTarget resolve(Logical.Binding binding, String path) {
        Class<?> marker;
        if (binding.kind() == Logical.BindingKind.OPERATION) {
            marker = Operation.class;
        } else if (binding.kind() == Logical.BindingKind.POLICY) {
            marker = Policy.class;
        } else if (binding.kind() == Logical.BindingKind.PERSISTENT_POLICY) {
            marker = PersistentPolicy.class;
        } else {
            throw new IllegalStateException("Unknown BindingKind: " + binding.kind());
        }

        if (!marker.isAssignableFrom(binding.contract())) {
            problem("INVALID_BINDING", path, binding.contract().getName()
                    + " does not implement " + marker.getSimpleName());
        }
        Resolved resolved;
        if (binding.instance() != null) {
            Object instance = binding.instance();
            resolved = new Resolved(instance, implementation(instance, path));
        } else {
            BindingKey key = new BindingKey(binding.contract(), binding.qualifier(), binding.kind());
            String previousFailure = resolutionFailures.get(key);
            if (previousFailure != null) {
                problem("MISSING_BINDING", path, previousFailure);
                return null;
            }
            resolved = resolutions.get(key);
            if (resolved == null) {
                try {
                    Object instance = Objects.requireNonNull(
                            resolver.resolve(binding.contract(), binding.qualifier()),
                            "resolver returned null");
                    resolved = new Resolved(instance, implementation(instance, path));
                    resolutions.put(key, resolved);
                } catch (RuntimeException error) {
                    String message = error.getMessage() == null
                            ? "Cannot resolve " + binding.contract().getName()
                            : error.getMessage();
                    resolutionFailures.put(key, message);
                    problem("MISSING_BINDING", path, message);
                    return null;
                }
            }
        }
        if (!marker.isInstance(resolved.instance())) {
            problem("BINDING_TYPE", path, "Resolved object does not implement "
                    + marker.getSimpleName());
            return null;
        }
        if (binding.contract() != marker
                && !binding.contract().isInstance(resolved.instance())) {
            problem("BINDING_TYPE", path, "Resolved object does not implement "
                    + binding.contract().getName());
            return null;
        }
        return new PlanNode.BoundTarget(resolved.instance(), binding.contract(),
                resolved.implementation(), binding.qualifier());
    }

    /** 由 resolver 获取实现类描述；失败时回退为实例的实际类。 */
    private Class<?> implementation(Object instance, String path) {
        try {
            return Objects.requireNonNull(resolver.implementationClass(instance),
                    "implementation class");
        } catch (RuntimeException error) {
            problem("IMPLEMENTATION_CLASS", path, "Cannot describe resolved implementation");
            return instance.getClass();
        }
    }

    @Override
    public PlanNode required(String path) {
        PlanNode node = byPath.get(path);
        if (node == null) throw new IllegalStateException("Missing lowered child: " + path);
        return node;
    }

    @Override
    public Set<String> scopeNames() {
        return scopeNames;
    }

    @Override
    public void beginParallelBlock() {
        parallelBranchScopes.push(new HashSet<String>());
    }

    @Override
    public void endParallelBlock() {
        if (parallelBranchScopes.isEmpty()) {
            throw new IllegalStateException("Parallel branch scope stack is empty");
        }
        parallelBranchScopes.pop();
    }

    @Override
    public Set<String> branchNames() {
        if (parallelBranchScopes.isEmpty()) {
            throw new IllegalStateException(
                    "branchNames() must be called between beginParallelBlock()/endParallelBlock()");
        }
        return parallelBranchScopes.peek();
    }

    @Override
    public Map<String, ResumePoint<?>> resumePoints() {
        return resumePoints;
    }

    @Override
    public Map<String, PlanNode> byPath() {
        return byPath;
    }

    static NodeDescriptor descriptor(Work work, NodeDescriptor.Kind kind) {
        return NodeDescriptor.structural(work.path(), work.label(), kind);
    }

    @Override
    public void problem(String code, String path, String message) {
        problems.add(new FlowBuildException.Problem(code, path,
                message == null || message.trim().isEmpty() ? code : message));
    }
}
