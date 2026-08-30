package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Durable 运行时环境：统一管理存储、编解码器以及各版本的流程注册表。
 *
 * @author jay.wu
 */
public final class DurableRuntime {

    private final DurableStore store;
    private final StateMapper stateMapper;
    private final ConcurrentMap<String, DurableFlow<?, ?>> registeredFlows = new ConcurrentHashMap<>();

    private DurableRuntime(DurableStore store, StateMapper stateMapper) {
        this.store = Objects.requireNonNull(store, "DurableStore must not be null");
        this.stateMapper = stateMapper != null ? stateMapper : DefaultStateMapper.INSTANCE;
    }

    public static Builder builder(DurableStore store) {
        return new Builder(store);
    }

    public DurableStore store() {
        return store;
    }

    public StateMapper stateMapper() {
        return stateMapper;
    }

    /**
     * 注册特定版本的 Flow 定义。
     *
     * @param flow    流程定义，非 null
     * @param version 流程版本（正整数）
     * @param <I>     输入类型
     * @param <O>     输出类型
     * @return 注册后的 DurableFlow 实例
     */
    @SuppressWarnings("unchecked")
    public <I, O> DurableFlow<I, O> register(Flow<I, O> flow, int version) {
        Objects.requireNonNull(flow, "Flow must not be null");
        if (version <= 0) {
            throw new IllegalArgumentException("flowVersion must be a positive integer, got: " + version);
        }
        String key = flow.id() + ":" + version;
        DurableFlow<I, O> durableFlow = new DurableFlow<>(flow.id(), version, flow, store, stateMapper);
        DurableFlow<?, ?> existing = registeredFlows.putIfAbsent(key, durableFlow);
        if (existing != null) {
            throw new IllegalArgumentException("DurableFlow [" + flow.id() + "] version [" + version + "] is already registered");
        }
        return durableFlow;
    }

    /**
     * 获取已注册的特定版本 DurableFlow。
     *
     * @param flowId  流程 ID
     * @param version 流程版本
     * @param <I>     输入类型
     * @param <O>     输出类型
     * @return DurableFlow 实例，若未注册返回 null
     */
    @SuppressWarnings("unchecked")
    public <I, O> DurableFlow<I, O> get(String flowId, int version) {
        String key = flowId + ":" + version;
        return (DurableFlow<I, O>) registeredFlows.get(key);
    }

    public static final class Builder {
        private final DurableStore store;
        private StateMapper stateMapper;

        Builder(DurableStore store) {
            this.store = Objects.requireNonNull(store, "DurableStore must not be null");
        }

        public Builder stateMapper(StateMapper stateMapper) {
            this.stateMapper = stateMapper;
            return this;
        }

        public DurableRuntime build() {
            return new DurableRuntime(store, stateMapper);
        }
    }
}
