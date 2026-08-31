package com.team4u.framework.flow;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 逻辑编排抽象语法树（Logical AST）的内部密封节点接口族。
 *
 * <p>仅表达用户的编排逻辑意图，纯不可变且本身不可执行；由 {@link Compiler} 降级为 {@link PlanNode}。</p>
 *
 * @author team4u
 */
interface Logical {

    /** 绑定目标类型：Operation、Policy 或 PersistentPolicy。 */
    enum BindingKind {
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
    final class Binding {
        private final Object instance;
        private final Class<?> contract;
        private final String qualifier;
        private final BindingKind kind;
    }

    /** 绑定 Operation 的单步执行逻辑节点，携带输入投影与输出合并函数。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    final class Invoke implements Logical {
        private final Binding binding;
        private final Function<Object, Object> project;
        private final BiFunction<Object, Object, Object> merge;
    }

    /** 按序执行子节点的顺序流水线逻辑节点；scopeName 标记具名作用域边界。 */
    @Getter
    @Accessors(fluent = true)
    final class Sequence implements Logical {
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
    final class Route implements Logical {
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
    final class Fallback implements Logical {
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
    final class Parallel implements Logical {
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
    final class ParallelBranch {
        private final Branch<?, ?> token;
        private final Logical flow;
    }

    /** 异步挂起等待逻辑节点。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    final class Await implements Logical {
        private final ResumePoint<?> point;
    }

    /** 环绕治理控制逻辑节点（Policy / PersistentPolicy / Retry / Timeout）。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    final class Control implements Logical {
        /** 控制种类。 */
        public enum Kind {
            /** 内存策略。 */
            POLICY,
            /** 持久化策略。 */
            PERSISTENT_POLICY,
            /** 重试控制。 */
            RETRY,
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
    @AllArgsConstructor
    final class Complete implements Logical {
        private final Outcome<?> outcome;
        private final boolean identity;
    }

    /** 携带可读标签的装饰包装逻辑节点。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    final class Named implements Logical {
        private final String label;
        private final Logical body;
    }
}

