package com.team4u.framework.flow;

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

/**
 * 把不可变 Logical 树下降为运行时密封 PlanNode 树，并校验结构与绑定。
 * 采用工作列表后序遍历：先 normalize 子节点、再 build 当前节点，同时收集 FlowBuildException.Problem。
 */
final class Compiler {

    /**
     * 编译产物：root 节点、以路径索引的节点表、ResumePoint 名表，以及是否包含嵌套工作线程任务。
     */
    static final class Compiled {
        private final PlanNode root;
        private final Map<String, PlanNode> byPath;
        private final Map<String, ResumePoint<?>> resumePoints;
        private final boolean hasNestedWorkerTasks;
        private final boolean requiresCompensatingWorker;

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

        public PlanNode root() {
            return root;
        }

        public Map<String, PlanNode> byPath() {
            return byPath;
        }

        public Map<String, ResumePoint<?>> resumePoints() {
            return resumePoints;
        }

        public boolean hasNestedWorkerTasks() {
            return hasNestedWorkerTasks;
        }

        public boolean requiresCompensatingWorker() {
            return requiresCompensatingWorker;
        }
    }

    private static final class BindingKey {
        private final Class<?> contract;
        private final String qualifier;
        private final Logical.BindingKind kind;

        public BindingKey(Class<?> contract, String qualifier, Logical.BindingKind kind) {
            this.contract = contract;
            this.qualifier = qualifier;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BindingKey that = (BindingKey) o;
            return Objects.equals(contract, that.contract)
                    && Objects.equals(qualifier, that.qualifier)
                    && kind == that.kind;
        }

        @Override
        public int hashCode() {
            return Objects.hash(contract, qualifier, kind);
        }
    }

    private static final class Resolved {
        private final Object instance;
        private final Class<?> implementation;

        public Resolved(Object instance, Class<?> implementation) {
            this.instance = instance;
            this.implementation = implementation;
        }

        public Object instance() {
            return instance;
        }

        public Class<?> implementation() {
            return implementation;
        }
    }

    private static final class Work {
        private final Logical logical;
        private final String path;
        private final String label;
        private final boolean parallel;
        private final boolean build;

        public Work(Logical logical, String path, String label, boolean parallel, boolean build) {
            this.logical = logical;
            this.path = path;
            this.label = label;
            this.parallel = parallel;
            this.build = build;
        }

        public Logical logical() {
            return logical;
        }

        public String path() {
            return path;
        }

        public String label() {
            return label;
        }

        public boolean parallel() {
            return parallel;
        }

        public boolean build() {
            return build;
        }
    }

    private static final class Child {
        private final Logical logical;
        private final String path;
        private final boolean parallel;

        public Child(Logical logical, String path, boolean parallel) {
            this.logical = logical;
            this.path = path;
            this.parallel = parallel;
        }

        public Logical logical() {
            return logical;
        }

        public String path() {
            return path;
        }

        public boolean parallel() {
            return parallel;
        }
    }

    private static final class Normalized {
        private final Logical logical;
        private final String label;

        public Normalized(Logical logical, String label) {
            this.logical = logical;
            this.label = label;
        }

        public Logical logical() {
            return logical;
        }

        public String label() {
            return label;
        }
    }

    private final OperationResolver resolver;
    private final List<FlowBuildException.Problem> problems = new ArrayList<FlowBuildException.Problem>();
    private final Map<BindingKey, Resolved> resolutions = new HashMap<BindingKey, Resolved>();
    private final Map<BindingKey, String> resolutionFailures = new HashMap<BindingKey, String>();
    private final LinkedHashMap<String, PlanNode> byPath = new LinkedHashMap<String, PlanNode>();
    private final LinkedHashMap<String, ResumePoint<?>> resumePoints = new LinkedHashMap<String, ResumePoint<?>>();
    private final Set<String> scopeNames = new HashSet<String>();
    private final Set<String> branchNames = new HashSet<String>();

    private Compiler(OperationResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    /** 编译入口：下降 root 后若发现任何 Problem 则抛出 FlowBuildException。 */
    static Compiled compile(Flow<?, ?> flow, OperationResolver resolver) {
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
        work.addLast(new Work(root, "$", null, false, false));
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
        return byPath.get("$");
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

    /** 返回某节点的直接子 Logical 及其路径，Parallel 子项标记为 parallel=true。 */
    private List<Child> children(Work work) {
        List<Child> children = new ArrayList<Child>();
        if (work.logical() instanceof Logical.Sequence) {
            Logical.Sequence sequence = (Logical.Sequence) work.logical();
            for (int index = 0; index < sequence.children().size(); index++) {
                Logical child = sequence.children().get(index);
                children.add(new Child(child, childPath(work.path(), index), work.parallel()));
            }
        } else if (work.logical() instanceof Logical.Route) {
            Logical.Route route = (Logical.Route) work.logical();
            for (int index = 0; index < route.cases().size(); index++) {
                children.add(new Child(route.cases().get(index).branch(),
                        work.path() + "/case:" + index, work.parallel()));
            }
            if (route.otherwise() != null) {
                children.add(new Child(route.otherwise(),
                        work.path() + "/otherwise", work.parallel()));
            }
        } else if (work.logical() instanceof Logical.Fallback) {
            Logical.Fallback fallback = (Logical.Fallback) work.logical();
            for (int index = 0; index < fallback.branches().size(); index++) {
                children.add(new Child(fallback.branches().get(index),
                        work.path() + "/branch:" + index, work.parallel()));
            }
        } else if (work.logical() instanceof Logical.Parallel) {
            Logical.Parallel parallel = (Logical.Parallel) work.logical();
            for (int index = 0; index < parallel.branches().size(); index++) {
                children.add(new Child(parallel.branches().get(index).flow(),
                        work.path() + "/branch:" + index, true));
            }
        } else if (work.logical() instanceof Logical.Control) {
            Logical.Control control = (Logical.Control) work.logical();
            children.add(new Child(control.body(), work.path() + "/body", work.parallel()));
        }
        return children;
    }

    /** 根据 Logical 类型构造对应的 PlanNode，并校验 scope/branch/ResumePoint 唯一性与并行约束。 */
    private void build(Work work) {
        Logical logical = work.logical();
        PlanNode node;
        if (logical instanceof Logical.Invoke) {
            node = invoke((Logical.Invoke) logical, work.path(), work.label());
        } else if (logical instanceof Logical.Sequence) {
            Logical.Sequence sequence = (Logical.Sequence) logical;
            if (sequence.scopeName() != null && !scopeNames.add(sequence.scopeName())) {
                problem("DUPLICATE_SCOPE", work.path(), "Duplicate scope: " + sequence.scopeName());
            }
            List<PlanNode> children = new ArrayList<PlanNode>();
            for (int index = 0; index < sequence.children().size(); index++) {
                children.add(required(childPath(work.path(), index)));
            }
            node = new PlanNode.Sequence(descriptor(work, NodeDescriptor.Kind.SEQUENCE),
                    children, sequence.scopeName());
        } else if (logical instanceof Logical.Route) {
            Logical.Route route = (Logical.Route) logical;
            String selectorPath = work.path() + "/selector";
            PlanNode.Invoke selector = invoke(new Logical.Invoke(route.selector(),
                    value -> value, (ignored, value) -> value), selectorPath, null);
            byPath.put(selectorPath, selector);
            List<PlanNode.Route.RouteCase> cases = new ArrayList<PlanNode.Route.RouteCase>();
            for (int index = 0; index < route.cases().size(); index++) {
                cases.add(new PlanNode.Route.RouteCase(route.cases().get(index).key(),
                        required(work.path() + "/case:" + index)));
            }
            PlanNode otherwise = route.otherwise() == null ? null
                    : required(work.path() + "/otherwise");
            node = new PlanNode.Route(descriptor(work, NodeDescriptor.Kind.ROUTE),
                    selector, cases, otherwise);
        } else if (logical instanceof Logical.Fallback) {
            Logical.Fallback fallback = (Logical.Fallback) logical;
            List<PlanNode> branches = new ArrayList<PlanNode>();
            for (int index = 0; index < fallback.branches().size(); index++) {
                branches.add(required(work.path() + "/branch:" + index));
            }
            PlanNode.Fallback.Trigger trigger = fallback.trigger() == Logical.Fallback.Trigger.SKIPPED
                    ? PlanNode.Fallback.Trigger.SKIPPED : PlanNode.Fallback.Trigger.FAILED;
            node = new PlanNode.Fallback(descriptor(work, NodeDescriptor.Kind.FALLBACK),
                    trigger, branches);
        } else if (logical instanceof Logical.Parallel) {
            Logical.Parallel parallel = (Logical.Parallel) logical;
            List<PlanNode.ParallelBranch> branches = new ArrayList<PlanNode.ParallelBranch>();
            for (int index = 0; index < parallel.branches().size(); index++) {
                Branch<?, ?> token = parallel.branches().get(index).token();
                if (!branchNames.add(token.name())) {
                    problem("DUPLICATE_BRANCH", work.path(), "Duplicate branch: " + token.name());
                }
                branches.add(new PlanNode.ParallelBranch(token,
                        required(work.path() + "/branch:" + index)));
            }
            node = new PlanNode.Parallel(descriptor(work, NodeDescriptor.Kind.PARALLEL),
                    branches, parallel.join());
        } else if (logical instanceof Logical.Await) {
            Logical.Await await = (Logical.Await) logical;
            if (work.parallel()) {
                problem("PARALLEL_AWAIT", work.path(), "Parallel branches cannot await");
            }
            ResumePoint<?> existing = resumePoints.putIfAbsent(await.point().name(), await.point());
            if (existing != null) {
                problem("DUPLICATE_RESUME_POINT", work.path(),
                        "Duplicate ResumePoint name " + await.point().name());
            }
            node = new PlanNode.Await(descriptor(work, NodeDescriptor.Kind.AWAIT), await.point());
        } else if (logical instanceof Logical.Control) {
            Logical.Control control = (Logical.Control) logical;
            if (work.parallel() && control.kind() == Logical.Control.Kind.PERSISTENT_POLICY) {
                problem("PARALLEL_PERSISTENT_POLICY", work.path(),
                        "Parallel branches cannot use PersistentPolicy");
            }
            PlanNode.BoundTarget target = control.binding() == null ? null
                    : resolve(control.binding(), work.path());
            PlanNode.Control.Kind kind = PlanNode.Control.Kind.valueOf(control.kind().name());
            node = new PlanNode.Control(descriptor(work, NodeDescriptor.Kind.CONTROL), kind,
                    required(work.path() + "/body"), target,
                    control.keyProjection(), control.configuration());
        } else if (logical instanceof Logical.Complete) {
            Logical.Complete complete = (Logical.Complete) logical;
            node = new PlanNode.Complete(descriptor(work, NodeDescriptor.Kind.COMPLETE),
                    complete.outcome(), complete.identity());
        } else {
            throw new IllegalStateException("Unknown logical node: " + logical.getClass());
        }
        if (byPath.put(work.path(), node) != null) {
            problem("DUPLICATE_PATH", work.path(), "Compiler generated a duplicate path");
        }
    }

    private PlanNode.Invoke invoke(Logical.Invoke invoke, String path, String label) {
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
    private PlanNode.BoundTarget resolve(Logical.Binding binding, String path) {
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

    private PlanNode required(String path) {
        PlanNode node = byPath.get(path);
        if (node == null) throw new IllegalStateException("Missing lowered child: " + path);
        return node;
    }

    private static String childPath(String parent, int index) {
        return parent + "/" + index;
    }

    private static NodeDescriptor descriptor(Work work, NodeDescriptor.Kind kind) {
        return NodeDescriptor.structural(work.path(), work.label(), kind);
    }

    private void problem(String code, String path, String message) {
        problems.add(new FlowBuildException.Problem(code, path,
                message == null || message.trim().isEmpty() ? code : message));
    }
}
