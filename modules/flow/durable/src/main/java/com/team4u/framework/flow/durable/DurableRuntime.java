package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.FlowObserver;
import com.team4u.framework.flow.OperationResolver;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Durable 运行时环境：持有存储、状态编解码器、观察者与可选的调用方线程池，
 * 并负责把逻辑 Flow 编译为可跨进程恢复的 {@link DurableExecutable}。
 *
 * <p>运行时不拥有任何线程资源：{@code executor} 仅被借用（用于超时 worker 与异步命令），
 * 生命周期完全由调用方管理，运行时绝不创建或关闭线程池。</p>
 *
 * <p><b>并行分支串行驱动</b>：Durable 的 Parallel 分支按声明顺序串行驱动
 * （前序分支完成后才开始后序），不做并发执行——这是崩溃一致性合同允许的简化。
 * 需要真实并发执行时请使用 Core 的 Local 执行器。</p>
 *
 * <p><b>StateMapper 确定性契约</b>：resume 信号的幂等比较依赖
 * {@link StateMapper#encode} 的确定性——同一信号值多次编码必须产生
 * {@code equals} 相等的 {@link StoredValue}，否则幂等重驱动会被误判为
 * RESUME_SIGNAL_CONFLICT。选用 {@link DefaultStateMapper#INSTANCE} 或
 * 自定义 mapper 时都必须遵守该契约。</p>
 */
public final class DurableRuntime {

    private final DurableStore store;
    private final StateMapper stateMapper;
    private final OperationResolver operationResolver;
    private final FlowObserver observer;
    private final DurableObserver durableObserver;
    private final ExecutorService executor;

    private DurableRuntime(Builder builder) {
        this.store = builder.store;
        this.stateMapper = builder.stateMapper;
        this.operationResolver = builder.operationResolver;
        this.observer = builder.observer;
        this.durableObserver = builder.durableObserver;
        this.executor = builder.executor;
    }

    public static Builder builder(DurableStore store) {
        return new Builder(store);
    }

    /**
     * 编译指定版本的 Flow 为可执行的 DurableExecutable。
     * 编译通过 Core 公开投影 SPI 进行；返回的 executable 仅持有绑定实例引用。
     *
     * <p>文档化降级：计划含 TIMEOUT 且未配置 executor 时不会拒绝编译——
     * 超时不再由 worker 强制截止，而是依赖循环顶部的协作检查点与
     * invoke 入口的剩余时间检查（未到期时 body 同步执行）。</p>
     */
    public <I, O> DurableExecutable<I, O> compile(Flow<I, O> flow, String flowId, int flowVersion) {
        Objects.requireNonNull(flow, "flow must not be null");
        DurablePlanCompiler.Definition definition = DurablePlanCompiler.compile(
                flow, operationResolver);
        return new DurableExecutable<I, O>(flowId, flowVersion, definition, store,
                stateMapper, observer, durableObserver, executor);
    }

    public DurableStore store() {
        return store;
    }

    public StateMapper stateMapper() {
        return stateMapper;
    }

    public static final class Builder {
        private final DurableStore store;
        private StateMapper stateMapper = DefaultStateMapper.INSTANCE;
        private OperationResolver operationResolver = OperationResolver.rejecting();
        private FlowObserver observer = FlowObserver.noop();
        private DurableObserver durableObserver = DurableObserver.noop();
        private ExecutorService executor;

        Builder(DurableStore store) {
            this.store = Objects.requireNonNull(store, "DurableStore must not be null");
        }

        public Builder stateMapper(StateMapper stateMapper) {
            this.stateMapper = stateMapper != null ? stateMapper : DefaultStateMapper.INSTANCE;
            return this;
        }

        public Builder operationResolver(OperationResolver operationResolver) {
            this.operationResolver = Objects.requireNonNull(
                    operationResolver, "operationResolver must not be null");
            return this;
        }

        public Builder observer(FlowObserver observer) {
            this.observer = observer != null ? observer : FlowObserver.noop();
            return this;
        }

        public Builder durableObserver(DurableObserver durableObserver) {
            this.durableObserver = durableObserver != null ? durableObserver : DurableObserver.noop();
            return this;
        }

        /** 配置调用方拥有的线程池，仅借用、绝不关闭。 */
        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public DurableRuntime build() {
            return new DurableRuntime(this);
        }
    }
}
