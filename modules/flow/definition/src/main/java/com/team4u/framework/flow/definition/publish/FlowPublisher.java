package com.team4u.framework.flow.definition.publish;

import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.binding.FlowBinder;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.spi.OperationResolver;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流程发布管理服务（Flow Publisher）。
 *
 * <p>负责流程定义的校验、绑定与原子发布管理；严格保障进程内已发布版本的不可变性（Process-local immutable flowId + flowVersion）。</p>
 *
 * @author jay.wu
 */
public class FlowPublisher {

    private final FlowDefinitionRegistry registry;
    private final OperationResolver resolver;
    private final ConcurrentHashMap<FlowKey, BoundFlow> publishedFlows = new ConcurrentHashMap<FlowKey, BoundFlow>();

    public FlowPublisher(FlowDefinitionRegistry registry, OperationResolver resolver) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.resolver = resolver != null ? resolver : OperationResolver.defaultResolver();
    }

    public FlowPublisher(FlowDefinitionRegistry registry) {
        this(registry, OperationResolver.defaultResolver());
    }

    /**
     * 原子校验并发布流程定义。
     *
     * @param definition 待发布的流程定义
     * @return 绑定成功后的 BoundFlow
     * @throws IllegalStateException 当尝试原地覆盖已发布的同名同版本流程时抛出
     */
    public BoundFlow publish(FlowDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        FlowKey key = FlowKey.of(definition.id(), definition.version());

        if (publishedFlows.containsKey(key)) {
            throw new IllegalStateException(
                    "Flow definition is immutable once published: " + key);
        }

        BoundFlow bound = FlowBinder.bind(definition, registry, resolver);
        BoundFlow existing = publishedFlows.putIfAbsent(key, bound);
        if (existing != null) {
            throw new IllegalStateException(
                    "Flow definition is immutable once published: " + key);
        }
        return bound;
    }

    /**
     * 获取指定唯一键已发布的流程。
     *
     * @param key 流程唯一键
     * @return 绑定的流程（未发布时返回 null）
     */
    public BoundFlow get(FlowKey key) {
        if (key == null) {
            return null;
        }
        return publishedFlows.get(key);
    }

    /**
     * 获取指定版本已发布的流程。
     *
     * @param flowId      流程唯一标识
     * @param flowVersion 流程版本
     * @return 绑定的流程（未发布时返回 null）
     */
    public BoundFlow get(String flowId, String flowVersion) {
        if (flowId == null) {
            return null;
        }
        return get(FlowKey.of(flowId, flowVersion));
    }

    /**
     * 获取默认版本（"1"）已发布的流程。
     *
     * @param flowId 流程唯一标识
     * @return 绑定的流程（未发布时返回 null）
     */
    public BoundFlow get(String flowId) {
        return get(flowId, "1");
    }

    /**
     * 获取全部已发布流程只读视图。
     *
     * @return 已发布流程 Map
     */
    public Map<FlowKey, BoundFlow> publishedFlows() {
        return Collections.unmodifiableMap(publishedFlows);
    }

    /**
     * 流程唯一标识与版本联合键（Flow Key），杜绝复合字符串拼接冲突。
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode
    @ToString
    public static final class FlowKey implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String flowId;
        private final String flowVersion;

        public FlowKey(String flowId, String flowVersion) {
            this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
            this.flowVersion = flowVersion != null ? flowVersion : "1";
        }

        public static FlowKey of(String flowId, String flowVersion) {
            return new FlowKey(flowId, flowVersion);
        }
    }
}
