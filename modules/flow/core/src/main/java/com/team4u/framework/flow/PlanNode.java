package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 密封的运行时计划节点，由 {@link Compiler} 从 {@link Logical} 投影而来，可执行。
 * 子类型固定为 Invoke/Sequence/Route/Fallback/Parallel/Await/Control/Complete。
 */
interface PlanNode {
    NodeDescriptor descriptor();

    /** 已解析的绑定目标：实例、契约类型、实现类与 qualifier。 */
    final class BoundTarget {
        private final Object instance;
        private final Class<?> contract;
        private final Class<?> implementation;
        private final String qualifier;

        public BoundTarget(Object instance, Class<?> contract, Class<?> implementation, String qualifier) {
            this.instance = instance;
            this.contract = contract;
            this.implementation = implementation;
            this.qualifier = qualifier;
        }

        public Object instance() {
            return instance;
        }

        public Class<?> contract() {
            return contract;
        }

        public Class<?> implementation() {
            return implementation;
        }

        public String qualifier() {
            return qualifier;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BoundTarget that = (BoundTarget) o;
            return Objects.equals(instance, that.instance)
                    && Objects.equals(contract, that.contract)
                    && Objects.equals(implementation, that.implementation)
                    && Objects.equals(qualifier, that.qualifier);
        }

        @Override
        public int hashCode() {
            return Objects.hash(instance, contract, implementation, qualifier);
        }
    }

    /** 绑定 Operation 的单步执行节点。 */
    final class Invoke implements PlanNode {
        private final NodeDescriptor descriptor;
        private final BoundTarget operation;
        private final Function<Object, Object> project;
        private final BiFunction<Object, Object, Object> merge;

        public Invoke(NodeDescriptor descriptor, BoundTarget operation,
                      Function<Object, Object> project, BiFunction<Object, Object, Object> merge) {
            this.descriptor = descriptor;
            this.operation = operation;
            this.project = project;
            this.merge = merge;
        }

        @Override
        public NodeDescriptor descriptor() {
            return descriptor;
        }

        public BoundTarget operation() {
            return operation;
        }

        public Function<Object, Object> project() {
            return project;
        }

        public BiFunction<Object, Object, Object> merge() {
            return merge;
        }
    }

    /** 按序执行 children 的顺序节点；scopeName 标记同一作用域。 */
    final class Sequence implements PlanNode {
        private final NodeDescriptor descriptor;
        private final List<PlanNode> children;
        private final String scopeName;

        public Sequence(NodeDescriptor descriptor, List<PlanNode> children, String scopeName) {
            this.descriptor = descriptor;
            this.children = Collections.unmodifiableList(new ArrayList<PlanNode>(children));
            this.scopeName = scopeName;
        }

        @Override
        public NodeDescriptor descriptor() {
            return descriptor;
        }

        public List<PlanNode> children() {
            return children;
        }

        public String scopeName() {
            return scopeName;
        }
    }

    /** 路由节点：selector 选出 key 后分发到命中 case 或 otherwise。 */
    final class Route implements PlanNode {
        private final NodeDescriptor descriptor;
        private final Invoke selector;
        private final List<RouteCase> cases;
        private final PlanNode otherwise;

        public Route(NodeDescriptor descriptor, Invoke selector, List<RouteCase> cases, PlanNode otherwise) {
            this.descriptor = descriptor;
            this.selector = selector;
            this.cases = Collections.unmodifiableList(new ArrayList<RouteCase>(cases));
            this.otherwise = otherwise;
        }

        @Override
        public NodeDescriptor descriptor() {
            return descriptor;
        }

        public Invoke selector() {
            return selector;
        }

        public List<RouteCase> cases() {
            return cases;
        }

        public PlanNode otherwise() {
            return otherwise;
        }

        public static final class RouteCase {
            private final Object key;
            private final PlanNode branch;

            public RouteCase(Object key, PlanNode branch) {
                this.key = key;
                this.branch = branch;
            }

            public Object key() {
                return key;
            }

            public PlanNode branch() {
                return branch;
            }
        }
    }

    /** 降级节点：trigger（SKIPPED 或 FAILED）触发时按序尝试 branches。 */
    final class Fallback implements PlanNode {
        public enum Trigger { SKIPPED, FAILED }

        private final NodeDescriptor descriptor;
        private final Trigger trigger;
        private final List<PlanNode> branches;

        public Fallback(NodeDescriptor descriptor, Trigger trigger, List<PlanNode> branches) {
            this.descriptor = descriptor;
            this.trigger = trigger;
            this.branches = Collections.unmodifiableList(new ArrayList<PlanNode>(branches));
        }

        @Override
        public NodeDescriptor descriptor() {
            return descriptor;
        }

        public Trigger trigger() {
            return trigger;
        }

        public List<PlanNode> branches() {
            return branches;
        }
    }

    /** 并行节点：分支 wait-all 执行后由 join 合并为单个 Outcome。 */
    final class Parallel implements PlanNode {
        private final NodeDescriptor descriptor;
        private final List<ParallelBranch> branches;
        private final JoinStrategy<?> join;

        public Parallel(NodeDescriptor descriptor, List<ParallelBranch> branches, JoinStrategy<?> join) {
            this.descriptor = descriptor;
            this.branches = Collections.unmodifiableList(new ArrayList<ParallelBranch>(branches));
            this.join = join;
        }

        @Override
        public NodeDescriptor descriptor() {
            return descriptor;
        }

        public List<ParallelBranch> branches() {
            return branches;
        }

        public JoinStrategy<?> join() {
            return join;
        }
    }

    /** Parallel 分支声明，token 命名分支。 */
    final class ParallelBranch {
        private final Branch<?, ?> token;
        private final PlanNode plan;

        public ParallelBranch(Branch<?, ?> token, PlanNode plan) {
            this.token = token;
            this.plan = plan;
        }

        public Branch<?, ?> token() {
            return token;
        }

        public PlanNode plan() {
            return plan;
        }
    }

    /** 挂起节点，在指定 ResumePoint 暂停等待 resume。 */
    final class Await implements PlanNode {
        private final NodeDescriptor descriptor;
        private final ResumePoint<?> point;

        public Await(NodeDescriptor descriptor, ResumePoint<?> point) {
            this.descriptor = descriptor;
            this.point = point;
        }

        @Override
        public NodeDescriptor descriptor() {
            return descriptor;
        }

        public ResumePoint<?> point() {
            return point;
        }
    }

    /** 控制节点：在 body 外包裹 Policy/PersistentPolicy/Retry/Timeout。 */
    final class Control implements PlanNode {
        public enum Kind { POLICY, PERSISTENT_POLICY, RETRY, TIMEOUT }

        private final NodeDescriptor descriptor;
        private final Kind kind;
        private final PlanNode body;
        private final BoundTarget policy;
        private final Function<Object, Object> keyProjection;
        private final Object configuration;

        public Control(NodeDescriptor descriptor, Kind kind, PlanNode body,
                       BoundTarget policy, Function<Object, Object> keyProjection, Object configuration) {
            this.descriptor = descriptor;
            this.kind = kind;
            this.body = body;
            this.policy = policy;
            this.keyProjection = keyProjection;
            this.configuration = configuration;
        }

        @Override
        public NodeDescriptor descriptor() {
            return descriptor;
        }

        public Kind kind() {
            return kind;
        }

        public PlanNode body() {
            return body;
        }

        public BoundTarget policy() {
            return policy;
        }

        public Function<Object, Object> keyProjection() {
            return keyProjection;
        }

        public Object configuration() {
            return configuration;
        }
    }

    /** 直接结束节点：identity=true 时以当前 entry 作为 Accepted 输出，否则使用给定 Outcome。 */
    final class Complete implements PlanNode {
        private final NodeDescriptor descriptor;
        private final Outcome<?> outcome;
        private final boolean identity;

        public Complete(NodeDescriptor descriptor, Outcome<?> outcome, boolean identity) {
            this.descriptor = descriptor;
            this.outcome = outcome;
            this.identity = identity;
        }

        @Override
        public NodeDescriptor descriptor() {
            return descriptor;
        }

        public Outcome<?> outcome() {
            return outcome;
        }

        public boolean identity() {
            return identity;
        }
    }
}
