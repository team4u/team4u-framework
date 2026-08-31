package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 流程生命周期与节点执行事件观察者 SPI（可用于链路追踪、指标监控、日志审计等）。
 *
 * <p>设计与异常隔离契约：
 * <ul>
 *   <li><b>异常安全隔离</b>：观察者在回调中抛出的任何异常都会被框架底层捕获并静默隔离，绝不会影响主流程的正常编排与执行结果；</li>
 *   <li><b>细粒度事件流</b>：涵盖流程生命周期（STARTED/COMPLETED/SUSPENDED/CANCELLED）、节点生命周期（STARTED/COMPLETED）、路由分支选择、策略判定（BEFORE/AFTER/WAITING）以及并行分支状态（STARTED/BRANCH_COMPLETED/JOINED）等；</li>
 *   <li><b>组合广播</b>：通过 {@link #composite(FlowObserver...)} 支持将多个观察者组合广播。</li>
 * </ul>
 * </p>
 *
 * @author team4u
 */
@FunctionalInterface
public interface FlowObserver {

    /**
     * 流程执行事件类型枚举。
     */
    enum Type {
        /** 流程开始执行。 */
        FLOW_STARTED,
        /** 流程执行完成。 */
        FLOW_COMPLETED,
        /** 流程进入挂起态。 */
        FLOW_SUSPENDED,
        /** 流程被取消。 */
        FLOW_CANCELLED,
        /** 单个节点开始执行。 */
        NODE_STARTED,
        /** 单个节点执行完成。 */
        NODE_COMPLETED,
        /** 路由分支命中选择。 */
        ROUTE_SELECTED,
        /** 降级分支命中选择。 */
        FALLBACK_SELECTED,
        /** 策略前置检查前。 */
        POLICY_BEFORE,
        /** 策略后置观察。 */
        POLICY_AFTER,
        /** 策略进入等待退避。 */
        POLICY_WAITING,
        /** 并行分支开始。 */
        PARALLEL_STARTED,
        /** 单个并行分支完成。 */
        PARALLEL_BRANCH_COMPLETED,
        /** 并行分支全部完成并汇聚。 */
        PARALLEL_JOINED
    }

    /**
     * 流程执行事件对象。
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode
    @ToString
    final class Event {
        /** 事件类型。 */
        private final Type type;
        /** 事件发生的绝对时间戳。 */
        private final Instant at;
        /** 关联的节点运行时元数据。 */
        private final Metadata metadata;
        /** 关联的静态节点描述符。 */
        private final NodeDescriptor descriptor;
        /** 结构化键值属性字典（不可变集合）。 */
        private final Map<String, String> attributes;

        /**
         * 构造流程事件对象。
         *
         * @param type       事件类型，不能为 null
         * @param at         发生时间，不能为 null
         * @param metadata   元数据，不能为 null
         * @param descriptor 节点描述符，不能为 null
         * @param attributes 扩展属性，不能为 null
         * @throws NullPointerException 当任何入参为 null 时抛出
         */
        public Event(Type type, Instant at, Metadata metadata,
                     NodeDescriptor descriptor, Map<String, String> attributes) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.at = Objects.requireNonNull(at, "at must not be null");
            this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(attributes, "attributes must not be null");
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
        }
    }

    /**
     * 接收并处理流程事件。
     *
     * @param event 流程事件对象，保证非 null
     */
    void onEvent(Event event);

    /**
     * 创建无操作的空观察者（单例）。
     *
     * @return 不做任何处理的 {@link FlowObserver}
     */
    static FlowObserver noop() {
        return new FlowObserver() {
            @Override
            public void onEvent(Event event) {
            }
        };
    }

    /**
     * 将多个观察者组合为一个复合观察者，按传入顺序广播。
     *
     * @param observers 观察者数组，不能为 null 且元素不能为 null
     * @return 复合 {@link FlowObserver} 实例
     * @throws NullPointerException 当参数或其元素为 null 时抛出
     */
    static FlowObserver composite(FlowObserver... observers) {
        Objects.requireNonNull(observers, "observers must not be null");
        final List<FlowObserver> copy = new ArrayList<FlowObserver>();
        for (FlowObserver observer : observers) {
            copy.add(Objects.requireNonNull(observer, "observer must not be null"));
        }
        return new FlowObserver() {
            @Override
            public void onEvent(Event event) {
                for (FlowObserver observer : copy) {
                    try {
                        observer.onEvent(event);
                    } catch (RuntimeException ignored) {
                        // Observer failures cannot alter execution.
                    }
                }
            }
        };
    }
}

