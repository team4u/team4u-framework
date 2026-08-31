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
 * 运行时密封物理执行计划节点（PlanNode）接口族。
 *
 * <p>由 {@link Compiler} 对 {@link Logical} 进行静态验证与 Bean 解析后生成，具有固定的拓扑路径与类型化属性，
 * 在 {@link SerialMachine} 驱动下完成内存无栈递归执行。</p>
 *
 * @author jay.wu
 */
interface PlanNode {
    /** 获取节点的静态描述符（包含拓扑路径与绑定类信息）。 */
    NodeDescriptor descriptor();

    /** 编译期解析绑定的实际目标组件信息。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    @EqualsAndHashCode
    final class BoundTarget {
        private final Object instance;
        private final Class<?> contract;
        private final Class<?> implementation;
        private final String qualifier;
    }

    /** 原子业务操作（Operation）执行计划节点。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    final class Invoke implements PlanNode {
        private final NodeDescriptor descriptor;
        private final BoundTarget operation;
        private final Function<Object, Object> project;
        private final BiFunction<Object, Object, Object> merge;
    }

    /** 顺序流水线/作用域执行计划节点。 */
    @Getter
    @Accessors(fluent = true)
    final class Sequence implements PlanNode {
        private final NodeDescriptor descriptor;
        private final List<PlanNode> children;
        private final String scopeName;

        public Sequence(NodeDescriptor descriptor, List<PlanNode> children, String scopeName) {
            this.descriptor = descriptor;
            this.children = Collections.unmodifiableList(new ArrayList<PlanNode>(children));
            this.scopeName = scopeName;
        }
    }

    /** 动态条件路由执行计划节点。 */
    @Getter
    @Accessors(fluent = true)
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

        /** 条件路由物理分支。 */
        @Getter
        @Accessors(fluent = true)
        @AllArgsConstructor
        public static final class RouteCase {
            private final Object key;
            private final PlanNode branch;
        }
    }

    /** 降级恢复执行计划节点。 */
    @Getter
    @Accessors(fluent = true)
    final class Fallback implements PlanNode {
        /** 降级触发条件。 */
        public enum Trigger {
            /** 弃权跳过。 */
            SKIPPED,
            /** 异常失败。 */
            FAILED
        }

        private final NodeDescriptor descriptor;
        private final Trigger trigger;
        private final List<PlanNode> branches;

        public Fallback(NodeDescriptor descriptor, Trigger trigger, List<PlanNode> branches) {
            this.descriptor = descriptor;
            this.trigger = trigger;
            this.branches = Collections.unmodifiableList(new ArrayList<PlanNode>(branches));
        }
    }

    /** 结构化并行并发执行计划节点。 */
    @Getter
    @Accessors(fluent = true)
    final class Parallel implements PlanNode {
        private final NodeDescriptor descriptor;
        private final List<ParallelBranch> branches;
        private final JoinStrategy<?> join;

        public Parallel(NodeDescriptor descriptor, List<ParallelBranch> branches, JoinStrategy<?> join) {
            this.descriptor = descriptor;
            this.branches = Collections.unmodifiableList(new ArrayList<ParallelBranch>(branches));
            this.join = join;
        }
    }

    /** 并行单条分支物理计划模型。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    final class ParallelBranch {
        private final Branch<?, ?> token;
        private final PlanNode plan;
    }

    /** 异步挂起等待执行计划节点。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    final class Await implements PlanNode {
        private final NodeDescriptor descriptor;
        private final ResumePoint<?> point;
    }

    /** 环绕治理控制执行计划节点（Policy / PersistentPolicy / Retry / Timeout）。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    final class Control implements PlanNode {
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

        private final NodeDescriptor descriptor;
        private final Kind kind;
        private final PlanNode body;
        private final BoundTarget policy;
        private final Function<Object, Object> keyProjection;
        private final Object configuration;
    }

    /** 常量或恒等透传终态执行计划节点。 */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    final class Complete implements PlanNode {
        private final NodeDescriptor descriptor;
        private final Outcome<?> outcome;
        private final boolean identity;
    }
}

