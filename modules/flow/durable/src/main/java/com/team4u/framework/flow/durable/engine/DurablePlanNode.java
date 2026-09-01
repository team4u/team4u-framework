package com.team4u.framework.flow.durable.engine;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import com.team4u.framework.flow.durable.DurableMachine;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.spi.ControlKind;
import com.team4u.framework.flow.spi.ExecutableBinding;
import com.team4u.framework.flow.spi.FallbackTrigger;
import com.team4u.framework.flow.spi.NodeDescriptor;

/**
 * 持久化模块专用的物理执行计划节点接口族（Durable Plan Node Family）。
 *
 * <p>由 {@link DurablePlanCompiler} 从 Core 物理节点投影生成，持有确定的插槽标识与绑定的实例引用，供 {@link DurableMachine} 驱动执行。</p>
 *
 * @author jay.wu
 */
public interface DurablePlanNode {
    /** 获取节点静态描述符。 */
    NodeDescriptor descriptor();


    @Getter
    @Accessors(fluent = true)
    public static final class Invoke implements DurablePlanNode {
        private final NodeDescriptor descriptor;
        private final ExecutableBinding binding;
        private final Function<Object, Object> project;
        private final BiFunction<Object, Object, Object> merge;
        public Invoke(NodeDescriptor descriptor, ExecutableBinding binding,
               Function<Object, Object> project,
               BiFunction<Object, Object, Object> merge) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
            this.binding = Objects.requireNonNull(binding, "binding must not be null");
            this.project = Objects.requireNonNull(project, "project must not be null");
            this.merge = Objects.requireNonNull(merge, "merge must not be null");
        }
    }

    @Getter
    @Accessors(fluent = true)
    public static final class Sequence implements DurablePlanNode {
        private final NodeDescriptor descriptor;
        private final List<DurablePlanNode> children;
        private final Optional<String> scopeName;
        public Sequence(NodeDescriptor descriptor, List<DurablePlanNode> children,
                 Optional<String> scopeName) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
            this.children = immutable(children, "children");
            this.scopeName = Objects.requireNonNull(scopeName, "scopeName must not be null");
        }
    }

    @Getter
    @Accessors(fluent = true)
    public static final class Route implements DurablePlanNode {
        private final NodeDescriptor descriptor;
        private final Invoke selector;
        private final List<RouteCase> cases;
        private final DurablePlanNode otherwise;
        public Route(NodeDescriptor descriptor, Invoke selector, List<RouteCase> cases,
              DurablePlanNode otherwise) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
            this.selector = Objects.requireNonNull(selector, "selector must not be null");
            this.cases = immutable(cases, "cases");
            this.otherwise = otherwise;
        }

        @Getter
        @Accessors(fluent = true)
        public static final class RouteCase {
            private final Object key;
            private final DurablePlanNode branch;
        public RouteCase(Object key, DurablePlanNode branch) {
                this.key = Objects.requireNonNull(key, "key must not be null");
                this.branch = Objects.requireNonNull(branch, "branch must not be null");
            }
        }
    }

    @Getter
    @Accessors(fluent = true)
    public static final class Fallback implements DurablePlanNode {
        private final NodeDescriptor descriptor;
        private final FallbackTrigger trigger;
        private final List<DurablePlanNode> branches;
        public Fallback(NodeDescriptor descriptor, FallbackTrigger trigger,
                 List<DurablePlanNode> branches) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
            this.trigger = Objects.requireNonNull(trigger, "trigger must not be null");
            this.branches = immutable(branches, "branches");
            if (this.branches.isEmpty()) {
                throw new IllegalArgumentException("fallback branches must not be empty");
            }
        }
    }

    @Getter
    @Accessors(fluent = true)
    public static final class Parallel implements DurablePlanNode {
        private final NodeDescriptor descriptor;
        private final List<ParallelBranch> branches;
        private final JoinStrategy<?> join;
        public Parallel(NodeDescriptor descriptor, List<ParallelBranch> branches,
                 JoinStrategy<?> join) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
            this.branches = immutable(branches, "branches");
            if (this.branches.isEmpty()) {
                throw new IllegalArgumentException("parallel branches must not be empty");
            }
            this.join = Objects.requireNonNull(join, "join must not be null");
        }

        @Getter
        @Accessors(fluent = true)
        public static final class ParallelBranch {
            private final Branch<?, ?> token;
            private final DurablePlanNode plan;
        public ParallelBranch(Branch<?, ?> token, DurablePlanNode plan) {
                this.token = Objects.requireNonNull(token, "token must not be null");
                this.plan = Objects.requireNonNull(plan, "plan must not be null");
            }
        }
    }

    @Getter
    @Accessors(fluent = true)
    public static final class Await implements DurablePlanNode {
        private final NodeDescriptor descriptor;
        private final ResumePoint<?> point;
        public Await(NodeDescriptor descriptor, ResumePoint<?> point) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
            this.point = Objects.requireNonNull(point, "point must not be null");
        }
    }

    @Getter
    @Accessors(fluent = true)
    public static final class Control implements DurablePlanNode {
        private final NodeDescriptor descriptor;
        private final ControlKind kind;
        private final DurablePlanNode body;
        private final Optional<ExecutableBinding> binding;
        private final Function<Object, Object> keyProjection;
        private final Object configuration;
        public Control(NodeDescriptor descriptor, ControlKind kind, DurablePlanNode body,
                Optional<ExecutableBinding> binding,
                Function<Object, Object> keyProjection, Object configuration) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
            this.kind = Objects.requireNonNull(kind, "kind must not be null");
            this.body = Objects.requireNonNull(body, "body must not be null");
            this.binding = Objects.requireNonNull(binding, "binding must not be null");
            this.keyProjection = Objects.requireNonNull(
                    keyProjection, "keyProjection must not be null");
            this.configuration = configuration;
            boolean policy = kind == ControlKind.POLICY
                    || kind == ControlKind.PERSISTENT_POLICY;
            if (policy != binding.isPresent()) {
                throw new IllegalArgumentException("control binding does not match kind " + kind);
            }
            if (kind == ControlKind.TIMEOUT && configuration == null) {
                throw new IllegalArgumentException("control configuration is required for " + kind);
            }
        }
    }

    @Getter
    @Accessors(fluent = true)
    public static final class Complete implements DurablePlanNode {
        private final NodeDescriptor descriptor;
        private final Outcome<?> outcome;
        private final boolean identity;
        public Complete(NodeDescriptor descriptor, Outcome<?> outcome, boolean identity) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
            this.outcome = outcome;
            this.identity = identity;
            if (identity == (outcome != null)) {
                throw new IllegalArgumentException(
                        "identity complete must not contain a fixed outcome");
            }
        }
    }

    static <T> List<T> immutable(List<T> source, String name) {
        Objects.requireNonNull(source, name + " must not be null");
        ArrayList<T> copy = new ArrayList<T>(source.size());
        for (T value : source) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return Collections.unmodifiableList(copy);
    }
}
