package com.team4u.framework.flow.compiler;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.model.Outcome;

/**
 * 逻辑编排抽象语法树（Logical AST）的内部密封节点接口族。
 *
 * <p>仅表达用户的编排逻辑意图，纯不可变且本身不可执行；由 {@link Compiler} 降级为 {@link PlanNode}。</p>
 *
 * @author jay.wu
 */
public interface Logical {

    /** 绑定目标类型：Operation、Policy 或 PersistentPolicy。 */
    public enum BindingKind {
        /** 原子业务步骤。 */
        OPERATION,
        /** 内存无状态策略。 */
        POLICY,
        /** 持久化有状态策略。 */
        PERSISTENT_POLICY
    }

    /** 编排期声明的组件绑定模型（包含实例、契约 Class、限定符与绑定种类）。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static final class Binding {
        private final Object instance;
        private final Class<?> contract;
        private final String qualifier;
        private final BindingKind kind;
    }

    /** 绑定 Operation 的单步执行逻辑节点，携带输入投影与输出合并函数。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    public static final class Invoke implements Logical {
        private final Binding binding;
        private final Function<Object, Object> project;
        private final BiFunction<Object, Object, Object> merge;
    }

    /** 按序执行子节点的顺序流水线逻辑节点；scopeName 标记具名作用域边界。 */
    @Getter
    @Accessors(fluent = true)
    public static final class Sequence implements Logical {
        private final List<Logical> children;
        private final String scopeName;

        public Sequence(List<Logical> children, String scopeName) {
            this.children = Collections.unmodifiableList(new ArrayList<Logical>(children));
            this.scopeName = scopeName;
        }
    }

    /** 条件路由逻辑节点：由 selector 提取判别键分发到匹配分支或 otherwise 分支。 */
    @Getter
    @Accessors(fluent = true)
    public static final class Route implements Logical {
        private final Binding selector;
        private final List<Case> cases;
        private final Logical otherwise;

        public Route(Binding selector, List<Case> cases, Logical otherwise) {
            this.selector = selector;
            this.cases = Collections.unmodifiableList(new ArrayList<Case>(cases));
            this.otherwise = otherwise;
        }

        /** 单个条件路由匹配分支。 */
        @Getter
        @Accessors(fluent = true)
        @AllArgsConstructor
        public static final class Case {
            private final Object key;
            private final Logical branch;
        }
    }

    /** 降级恢复逻辑节点：trigger 触发（SKIPPED 或 FAILED）时顺序尝试候选分支。 */
    @Getter
    @Accessors(fluent = true)
    public static final class Fallback implements Logical {
        /** 降级触发条件枚举。 */
        public enum Trigger {
            /** 弃权跳过触发。 */
            SKIPPED,
            /** 失败异常触发。 */
            FAILED
        }

        private final Trigger trigger;
        private final List<Logical> branches;

        public Fallback(Trigger trigger, List<Logical> branches) {
            this.trigger = trigger;
            this.branches = Collections.unmodifiableList(new ArrayList<Logical>(branches));
        }
    }

    /** 结构化并行并发逻辑节点：分支在并发池执行后由 JoinStrategy 汇聚归约。 */
    @Getter
    @Accessors(fluent = true)
    public static final class Parallel implements Logical {
        private final List<ParallelBranch> branches;
        private final JoinStrategy<?> join;

        public Parallel(List<ParallelBranch> branches, JoinStrategy<?> join) {
            this.branches = Collections.unmodifiableList(new ArrayList<ParallelBranch>(branches));
            this.join = join;
        }
    }

    /** 逻辑并行单条分支模型。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    public static final class ParallelBranch {
        private final Branch<?, ?> token;
        private final Logical flow;
    }

    /** 异步挂起等待逻辑节点。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    public static final class Await implements Logical {
        private final ResumePoint<?> point;
    }

    /** 环绕治理控制逻辑节点（Policy / PersistentPolicy / Timeout）。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    public static final class Control implements Logical {
        /** 控制种类。 */
        public enum Kind {
            /** 内存策略。 */
            POLICY,
            /** 持久化策略。 */
            PERSISTENT_POLICY,
            /** 超时时限。 */
            TIMEOUT
        }

        private final Kind kind;
        private final Logical body;
        private final Binding binding;
        private final Function<Object, Object> keyProjection;
        private final Object configuration;
    }

    /** 常量或恒等透传终态逻辑节点。 */
    @Getter
    @Accessors(fluent = true)
    public static final class Complete implements Logical {
        private final Outcome<?> outcome;
        private final boolean identity;

        private Complete(Outcome<?> outcome, boolean identity) {
            if (outcome == null && !identity) {
                throw new IllegalArgumentException(
                        "Complete requires a non-null outcome unless it is an identity node");
            }
            this.outcome = outcome;
            this.identity = identity;
        }

        /**
         * 创建恒等透传终态节点（输入直接作为 Accepted 输出）。
         *
         * @return 恒等透传节点
         */
        public static Complete identityNode() {
            return new Complete(null, true);
        }

        /**
         * 创建常量终态节点。
         *
         * @param outcome 固定输出结果，不能为 null
         * @return 常量终态节点
         * @throws NullPointerException 当 {@code outcome} 为 null 时抛出
         */
        public static Complete constant(Outcome<?> outcome) {
            return new Complete(
                    Objects.requireNonNull(outcome, "outcome must not be null"), false);
        }
    }

    /** 携带可读标签的装饰包装逻辑节点。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    public static final class Named implements Logical {
        private final String label;
        private final Logical body;
    }
}

