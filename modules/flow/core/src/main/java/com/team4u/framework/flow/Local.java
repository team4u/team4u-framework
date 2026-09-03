package com.team4u.framework.flow;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.compiler.Compiler;
import com.team4u.framework.flow.model.FlowBuildException;
import com.team4u.framework.flow.spi.BindingResolver;
import com.team4u.framework.flow.spi.OperationResolver;

/**
 * Local 内存执行器编译工厂门面。
 *
 * <p>负责将不可变 AST 逻辑流程定义（{@link Flow}）通过 {@link Compiler} 降级编译并静态验证为高效的内存可执行流 {@link LocalExecutable}。
 * 编译期会校验拓扑结构合法性（唯一 scope 名、唯一分支 token、唯一挂起点、禁止 parallel 内部非法 await 等），
 * 并使用提供的 {@link OperationResolver} 解析延迟绑定的组件。
 *
 * <p>线程池管理原则：
 * <ul>
 *   <li>若未显式指定 {@link ExecutorService}，默认借用 {@link ForkJoinPool#commonPool()} 进行并行分支及异步调用驱动；</li>
 *   <li>执行引擎仅“借用”线程池，绝不主动关闭外部传入的 {@link ExecutorService}。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public final class Local {
    private Local() { }

    /**
     * 编译逻辑流程（使用默认无 IoC 解析器、无操作观察者与 commonPool 线程池）。
     *
     * @param flow 逻辑流程定义，不能为 null
     * @param <I>  流程输入类型
     * @param <O>  流程输出类型
     * @return 编译就绪的 {@link LocalExecutable} 实例
     * @throws FlowBuildException 当流程定义存在拓扑冲突或非法节点时抛出
     */
    public static <I, O> LocalExecutable<I, O> compile(Flow<I, O> flow) {
        return from(flow).compile();
    }

    /**
     * 编译逻辑流程（注入自定义组件解析器）。
     *
     * <p><b>编译产物复用指南</b>：Flow 定义不可变且可复用，对同一 {@code flow} 实例的重复
     * {@code compile} 调用建议在外部缓存 {@link LocalExecutable}（或 {@link Compiler.Compiled}）
     * 并复用，避免每次调用重复降级编译与组件解析；编译产物本身线程安全，可并发驱动多次执行。
     * 若确需在库内缓存，参见 {@link #compileCached(Flow, BindingResolver)}。</p>
     *
     * @param flow     逻辑流程定义，不能为 null
     * @param resolver 组件解析器（如 Spring Bean 查找器），不能为 null
     * @param <I>      流程输入类型
     * @param <O>      流程输出类型
     * @return 编译就绪的 {@link LocalExecutable} 实例
     * @throws FlowBuildException 当流程定义存在拓扑冲突或无法解析的组件时抛出
     */
    public static <I, O> LocalExecutable<I, O> compile(
            Flow<I, O> flow, BindingResolver resolver) {
        return from(flow).resolver(resolver).compile();
    }

    /**
     * 以弱缓存复用编译产物编译逻辑流程。
     *
     * <p>{@code (flow, resolver)} 使用弱身份键；Compiled value 在缓存条目存活期间保持强引用，任一 key referent 被回收后由 ReferenceQueue 清理条目。
     * 一个 resolver 在被用作 compile cache key 的生命周期内，其 binding 映射必须稳定。
     * 并发安全：基于 ConcurrentHashMap，同一 (flow, resolver) 的并发首次编译可能重复执行一次，但返回的产物一致且线程安全。</p>
     *
     * <p>注意：缓存的仅是静态编译产物（拓扑与绑定解析结果），不含任何运行期状态；
     * observer 与线程池每次调用时重新绑定，不参与缓存键。</p>
     *
     * @param flow     逻辑流程定义，不能为 null
     * @param resolver 组件解析器，不能为 null
     * @param <I>      流程输入类型
     * @param <O>      流程输出类型
     * @return 编译就绪的 {@link LocalExecutable} 实例（同一 flow 和 resolver 实例返回复用产物的执行器）
     * @throws FlowBuildException 当流程定义存在拓扑冲突或无法解析的组件时抛出
     */
    public static <I, O> LocalExecutable<I, O> compileCached(
            Flow<I, O> flow, BindingResolver resolver) {
        return from(flow).resolver(resolver).cached().compile();
    }

    /**
     * 创建强类型 Fluent 编译构建器，自动推导流程输入输出泛型类型。
     *
     * @param flow 逻辑流程定义，不能为 null
     * @param <I>  流程输入类型
     * @param <O>  流程输出类型
     * @return Local 编译构建器实例
     * @throws NullPointerException 当 {@code flow} 为 null 时抛出
     */
    public static <I, O> Builder<I, O> from(Flow<I, O> flow) {
        return new Builder<I, O>(flow);
    }

    /**
     * Local 执行器流式编译构建器（Fluent Local Compiler Builder）。
     *
     * @param <I> 流程输入类型
     * @param <O> 流程输出类型
     */
    public static final class Builder<I, O> {
        private final Flow<I, O> flow;
        private String flowId = "local";
        private int flowVersion = 0;
        private BindingResolver resolver = BindingResolver.defaultResolver();
        private FlowObserver observer = FlowObserver.noop();
        private ExecutorService executor = ForkJoinPool.commonPool();
        private boolean cached = false;

        Builder(Flow<I, O> flow) {
            this.flow = Objects.requireNonNull(flow, "flow must not be null");
        }

        /**
         * 设置流程全局标识（参与 invocationId 与事件 Metadata）。
         *
         * @param flowId 流程标识，不能为 null 或空白
         * @return 当前构建器
         */
        public Builder<I, O> flowId(String flowId) {
            this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
            if (flowId.trim().isEmpty()) {
                throw new IllegalArgumentException("flowId must not be blank");
            }
            return this;
        }

        /**
         * 设置流程版本号。
         *
         * @param flowVersion 流程版本号
         * @return 当前构建器
         */
        public Builder<I, O> flowVersion(int flowVersion) {
            this.flowVersion = flowVersion;
            return this;
        }

        /**
         * 设置组件解析器（如 Spring Bean 查找器）。
         *
         * @param resolver 组件解析器（为 null 时回退为 defaultResolver）
         * @return 当前构建器
         */
        public Builder<I, O> resolver(BindingResolver resolver) {
            this.resolver = resolver != null ? resolver : BindingResolver.defaultResolver();
            return this;
        }

        /**
         * 设置流程事件观察者。
         *
         * @param observer 事件观察者（为 null 时回退为 noop 观察者）
         * @return 当前构建器
         */
        public Builder<I, O> observer(FlowObserver observer) {
            this.observer = observer != null ? observer : FlowObserver.noop();
            return this;
        }

        /**
         * 设置用于并发分支调度与异步驱动的工作线程池。
         *
         * @param executor 工作线程池（为 null 时回退为 ForkJoinPool.commonPool）
         * @return 当前构建器
         */
        public Builder<I, O> executor(ExecutorService executor) {
            this.executor = executor != null ? executor : ForkJoinPool.commonPool();
            return this;
        }

        /**
         * 设置是否开启弱缓存复用编译产物。
         *
         * @param cached 是否开启弱缓存
         * @return 当前构建器
         */
        public Builder<I, O> cached(boolean cached) {
            this.cached = cached;
            return this;
        }

        /**
         * 开启弱缓存复用编译产物。
         *
         * @return 当前构建器
         */
        public Builder<I, O> cached() {
            return cached(true);
        }

        /**
         * 编译逻辑流程并静态校验拓扑，产出内存可执行流实例。
         *
         * @return 编译就绪的 {@link LocalExecutable} 实例
         * @throws FlowBuildException 当流程定义存在拓扑冲突、命名冲突或组件未解析时抛出
         */
        public LocalExecutable<I, O> compile() {
            Compiler.Compiled compiled = cached
                    ? CompileCache.obtain(flow, resolver)
                    : Compiler.compile(flow, resolver);
            return LocalExecutable.create(compiled, flowId, flowVersion, observer, executor);
        }
    }

    /**
     * 基于 flow 与 resolver 身份键的编译产物弱缓存。
     *
     * <p>键为 (flow, resolver) 实例的弱引用（identity 语义），值为编译产物；并发首次编译可能重复执行，
     * 但返回一致产物。产物不含运行期状态，线程安全可并发复用。
     * 一个 resolver 在被用作 compile cache key 的生命周期内，其 binding 映射必须稳定。</p>
     */
    static final class CompileCache {
        private static final Map<Key, Compiler.Compiled> CACHE =
                new ConcurrentHashMap<Key, Compiler.Compiled>();
        private static final ReferenceQueue<Object> QUEUE =
                new ReferenceQueue<Object>();

        private static final class Key extends WeakReference<Flow<?, ?>> {
            private final ResolverRef resolverRef;
            private final int hash;

            Key(Flow<?, ?> flow, BindingResolver resolver, ReferenceQueue<Object> queue) {
                super(flow, queue != null ? (ReferenceQueue<? super Flow<?, ?>>) (ReferenceQueue<?>) queue : null);
                this.resolverRef = new ResolverRef(
                        resolver,
                        queue != null ? (ReferenceQueue<? super BindingResolver>) (ReferenceQueue<?>) queue : null,
                        this
                );
                this.hash = 31 * System.identityHashCode(flow) + System.identityHashCode(resolver);
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof Key)) return false;
                Key o = (Key) other;
                Flow<?, ?> mineFlow = get();
                Flow<?, ?> theirsFlow = o.get();
                if (mineFlow == null || mineFlow != theirsFlow) return false;
                BindingResolver mineResolver = resolverRef.get();
                BindingResolver theirsResolver = o.resolverRef.get();
                return mineResolver != null && mineResolver == theirsResolver;
            }
        }

        private static final class ResolverRef extends WeakReference<BindingResolver> {
            final Key key;

            ResolverRef(BindingResolver referent, ReferenceQueue<? super BindingResolver> queue, Key key) {
                super(referent, queue);
                this.key = key;
            }
        }

        static Compiler.Compiled obtain(Flow<?, ?> flow, BindingResolver resolver) {
            Objects.requireNonNull(flow, "flow must not be null");
            Objects.requireNonNull(resolver, "resolver must not be null");
            expunge();
            Key lookupKey = new Key(flow, resolver, null);
            Compiler.Compiled existing = CACHE.get(lookupKey);
            if (existing != null) {
                return existing;
            }
            Compiler.Compiled compiled = Compiler.compile(flow, resolver);
            Key registeredKey = new Key(flow, resolver, QUEUE);
            Compiler.Compiled raced = CACHE.putIfAbsent(registeredKey, compiled);
            if (raced != null) {
                return raced;
            }
            return compiled;
        }

        /** 清理已被 GC 回收的键条目。 */
        private static void expunge() {
            Object polled;
            while ((polled = QUEUE.poll()) != null) {
                if (polled instanceof Key) {
                    CACHE.remove(polled);
                } else if (polled instanceof ResolverRef) {
                    CACHE.remove(((ResolverRef) polled).key);
                }
            }
        }
    }
}

