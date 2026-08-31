package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 逻辑 DSL 的密封节点类型，描述不可变的编排意图，本身不可执行。
 * 由 {@link Compiler} 投影为可执行的 {@link PlanNode}。
 */
interface Logical {

    /** 绑定目标类型：Operation、Policy 或 PersistentPolicy。 */
    enum BindingKind { OPERATION, POLICY, PERSISTENT_POLICY }

    /** 编排期解析的绑定实例：实例、契约类型、qualifier 与绑定种类。 */
    final class Binding {
        private final Object instance;
        private final Class<?> contract;
        private final String qualifier;
        private final BindingKind kind;

        public Binding(Object instance, Class<?> contract, String qualifier, BindingKind kind) {
            this.instance = instance;
            this.contract = contract;
            this.qualifier = qualifier;
            this.kind = kind;
        }

        public Object instance() {
            return instance;
        }

        public Class<?> contract() {
            return contract;
        }

        public String qualifier() {
            return qualifier;
        }

        public BindingKind kind() {
            return kind;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Binding binding = (Binding) o;
            return Objects.equals(instance, binding.instance)
                    && Objects.equals(contract, binding.contract)
                    && Objects.equals(qualifier, binding.qualifier)
                    && kind == binding.kind;
        }

        @Override
        public int hashCode() {
            return Objects.hash(instance, contract, qualifier, kind);
        }
    }

    /** 绑定 Operation 的单步执行节点，携带输入投影与输出合并函数。 */
    final class Invoke implements Logical {
        private final Binding binding;
        private final Function<Object, Object> project;
        private final BiFunction<Object, Object, Object> merge;

        public Invoke(Binding binding, Function<Object, Object> project,
                      BiFunction<Object, Object, Object> merge) {
            this.binding = binding;
            this.project = project;
            this.merge = merge;
        }

        public Binding binding() {
            return binding;
        }

        public Function<Object, Object> project() {
            return project;
        }

        public BiFunction<Object, Object, Object> merge() {
            return merge;
        }
    }

    /** 按序执行 children 的顺序编排；scopeName 标记同一作用域，供降级/恢复复用。 */
    final class Sequence implements Logical {
        private final List<Logical> children;
        private final String scopeName;

        public Sequence(List<Logical> children, String scopeName) {
            this.children = Collections.unmodifiableList(new ArrayList<Logical>(children));
            this.scopeName = scopeName;
        }

        public List<Logical> children() {
            return children;
        }

        public String scopeName() {
            return scopeName;
        }
    }

    /** 路由编排：由 selector 选出 key，分发到命中 case 分支或 otherwise 默认分支。 */
    final class Route implements Logical {
        private final Binding selector;
        private final List<Case> cases;
        private final Logical otherwise;

        public Route(Binding selector, List<Case> cases, Logical otherwise) {
            this.selector = selector;
            this.cases = Collections.unmodifiableList(new ArrayList<Case>(cases));
            this.otherwise = otherwise;
        }

        public Binding selector() {
            return selector;
        }

        public List<Case> cases() {
            return cases;
        }

        public Logical otherwise() {
            return otherwise;
        }

        public static final class Case {
            private final Object key;
            private final Logical branch;

            public Case(Object key, Logical branch) {
                this.key = key;
                this.branch = branch;
            }

            public Object key() {
                return key;
            }

            public Logical branch() {
                return branch;
            }
        }
    }

    /** 降级编排：trigger 触发（SKIPPED 或 FAILED）时顺序尝试 branches。 */
    final class Fallback implements Logical {
        public enum Trigger { SKIPPED, FAILED }

        private final Trigger trigger;
        private final List<Logical> branches;

        public Fallback(Trigger trigger, List<Logical> branches) {
            this.trigger = trigger;
            this.branches = Collections.unmodifiableList(new ArrayList<Logical>(branches));
        }

        public Trigger trigger() {
            return trigger;
        }

        public List<Logical> branches() {
            return branches;
        }
    }

    /** 分支在线程池上 wait-all 执行后由 join 合并为单个 Outcome 的并行编排。 */
    final class Parallel implements Logical {
        private final List<ParallelBranch> branches;
        private final JoinStrategy<?> join;

        public Parallel(List<ParallelBranch> branches, JoinStrategy<?> join) {
            this.branches = Collections.unmodifiableList(new ArrayList<ParallelBranch>(branches));
            this.join = join;
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
        private final Logical flow;

        public ParallelBranch(Branch<?, ?> token, Logical flow) {
            this.token = token;
            this.flow = flow;
        }

        public Branch<?, ?> token() {
            return token;
        }

        public Logical flow() {
            return flow;
        }
    }

    /** 挂起点，在指定 ResumePoint 处暂停等待 resume 信号。 */
    final class Await implements Logical {
        private final ResumePoint<?> point;

        public Await(ResumePoint<?> point) {
            this.point = point;
        }

        public ResumePoint<?> point() {
            return point;
        }
    }

    /** 控制节点：包装 Policy/PersistentPolicy/Retry/Timeout 作用于 body。 */
    final class Control implements Logical {
        public enum Kind { POLICY, PERSISTENT_POLICY, RETRY, TIMEOUT }

        private final Kind kind;
        private final Logical body;
        private final Binding binding;
        private final Function<Object, Object> keyProjection;
        private final Object configuration;

        public Control(Kind kind, Logical body, Binding binding,
                       Function<Object, Object> keyProjection, Object configuration) {
            this.kind = kind;
            this.body = body;
            this.binding = binding;
            this.keyProjection = keyProjection;
            this.configuration = configuration;
        }

        public Kind kind() {
            return kind;
        }

        public Logical body() {
            return body;
        }

        public Binding binding() {
            return binding;
        }

        public Function<Object, Object> keyProjection() {
            return keyProjection;
        }

        public Object configuration() {
            return configuration;
        }
    }

    /** 直接结束节点：identity=true 时以当前 entry 作为 Accepted 输出，否则使用给定 Outcome。 */
    final class Complete implements Logical {
        private final Outcome<?> outcome;
        private final boolean identity;

        public Complete(Outcome<?> outcome, boolean identity) {
            this.outcome = outcome;
            this.identity = identity;
        }

        public Outcome<?> outcome() {
            return outcome;
        }

        public boolean identity() {
            return identity;
        }
    }

    /** 仅贴上可读 label 的包装节点，不影响执行结果。 */
    final class Named implements Logical {
        private final String label;
        private final Logical body;

        public Named(String label, Logical body) {
            this.label = label;
            this.body = body;
        }

        public String label() {
            return label;
        }

        public Logical body() {
            return body;
        }
    }
}
