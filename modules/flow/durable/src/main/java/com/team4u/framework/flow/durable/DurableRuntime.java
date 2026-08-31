package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.FlowObserver;
import com.team4u.framework.flow.OperationResolver;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * 耐久化流执行运行时环境（Durable Runtime Facade）。
 *
 * <p>负责配置并管理持久化组件、事件观察者、线程池资源与类型化编解码器。
 *
 * <p>核心契约与安全模型：
 * <ul>
 *   <li><b>外部线程池（Executor）生命周期借用</b>：用户通过 {@link Builder#executor} 传入的线程池，其生命周期完全由调用方管理（DurableRuntime 不负责关闭，使用完毕须由上层手动 shutdown）；</li>
 *   <li><b>StateMapper 确定性契约</b>：外部恢复信号（Resume Signal）的幂等校验依赖 {@link StateMapper#encode} 的确定性（同一信号值多次编码必须产生 {@code equals} 相等的 {@link StoredValue}），否则会引发冲突拒绝。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public final class DurableRuntime {

    @Getter
    @Accessors(fluent = true)
    private final DurableStore store;
    @Getter
    @Accessors(fluent = true)
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

    /**
     * 创建 DurableRuntime 构造器。
     *
     * @param store 持久化存储后端，不能为 null
     * @return 构造器实例
     * @throws NullPointerException 当 store 为 null 时抛出
     */
    public static Builder builder(DurableStore store) {
        return new Builder(store);
    }

    /**
     * 编译指定业务标识与版本的 Flow 为可持久化执行句柄 {@link DurableExecutable}。
     *
     * @param flow        逻辑编排定义，不能为 null
     * @param flowId      流程全局唯一标识，不能为空
     * @param flowVersion 流程版本号
     * @param <I>         流程输入类型
     * @param <O>         流程输出类型
     * @return 可执行句柄
     * @throws NullPointerException 当 flow 为 null 时抛出
     */
    public <I, O> DurableExecutable<I, O> compile(Flow<I, O> flow, String flowId, int flowVersion) {
        Objects.requireNonNull(flow, "flow must not be null");
        DurablePlanCompiler.Definition definition = DurablePlanCompiler.compile(
                flow, operationResolver);
        return new DurableExecutable<I, O>(flowId, flowVersion, definition, store,
                stateMapper, observer, durableObserver, executor);
    }

    /**
     * DurableRuntime 流式构建器。
     */
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

        /**
         * 设置业务状态编解码器。
         *
         * @param stateMapper 编解码器（为 null 时回退为 {@link DefaultStateMapper#INSTANCE}）
         * @return 当前构建器
         */
        public Builder stateMapper(StateMapper stateMapper) {
            this.stateMapper = stateMapper != null ? stateMapper : DefaultStateMapper.INSTANCE;
            return this;
        }

        /**
         * 设置依赖注入解析器。
         *
         * @param operationResolver 解析器，不能为 null
         * @return 当前构建器
         */
        public Builder operationResolver(OperationResolver operationResolver) {
            this.operationResolver = Objects.requireNonNull(
                    operationResolver, "operationResolver must not be null");
            return this;
        }

        /**
         * 设置通用流程观察者。
         *
         * @param observer 观察者
         * @return 当前构建器
         */
        public Builder observer(FlowObserver observer) {
            this.observer = observer != null ? observer : FlowObserver.noop();
            return this;
        }

        /**
         * 设置持久化专用生命周期观察者。
         *
         * @param durableObserver 持久化观察者
         * @return 当前构建器
         */
        public Builder durableObserver(DurableObserver durableObserver) {
            this.durableObserver = durableObserver != null ? durableObserver : DurableObserver.noop();
            return this;
        }

        /**
         * 配置调用方拥有的外部线程池（仅借用用于超时控制与异步派发，运行时绝不关闭该线程池）。
         *
         * @param executor 线程池
         * @return 当前构建器
         */
        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        /**
         * 构建持久化运行时实例。
         *
         * @return DurableRuntime 实例
         */
        public DurableRuntime build() {
            return new DurableRuntime(this);
        }
    }
}

