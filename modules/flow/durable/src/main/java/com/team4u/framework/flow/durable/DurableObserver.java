package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Metadata;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 持久化生命周期专用观察者 SPI 接口（Durable Observer SPI）。
 *
 * <p>用于监听检查点提交（{@link Type#CHECKPOINT_COMMITTED}）、崩溃恢复重建（{@link Type#CHECKPOINT_RESTORED}）
 * 以及挂起恢复信号持久化（{@link Type#RESUME_SIGNAL_PERSISTED}）等持久化特有事件。</p>
 *
 * @author team4u
 */
@FunctionalInterface
public interface DurableObserver {

    /**
     * 持久化生命周期事件类型枚举。
     */
    enum Type {
        /** 检查点快照成功 CAS 提交。 */
        CHECKPOINT_COMMITTED,
        /** 从持久化快照中恢复重建执行状态机。 */
        CHECKPOINT_RESTORED,
        /** 外部恢复信号成功写入持久化快照。 */
        RESUME_SIGNAL_PERSISTED
    }

    /**
     * 持久化事件数据对象。
     */
    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    final class Event {
        /** 事件类型。 */
        private final Type type;
        /** 事件发生时刻。 */
        private final Instant at;
        /** 拓扑元数据。 */
        private final Metadata metadata;
        /** 提交后的快照版本号。 */
        private final long revision;
        /** 快照生命周期。 */
        private final DurableLifecycle lifecycle;
        /** 附加属性键值对。 */
        private final Map<String, String> attributes;

        public Event(Type type, Instant at, Metadata metadata, long revision,
                     DurableLifecycle lifecycle, Map<String, String> attributes) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.at = Objects.requireNonNull(at, "at must not be null");
            this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
            if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
            this.revision = revision;
            this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
            Objects.requireNonNull(attributes, "attributes must not be null");
            this.attributes = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(attributes));
        }
    }

    /**
     * 接收持久化事件回调。
     *
     * @param event 事件对象
     */
    void onEvent(Event event);

    /**
     * 获取空操作观察者单例。
     *
     * @return No-op 观察者
     */
    static DurableObserver noop() {
        return new DurableObserver() {
            @Override public void onEvent(Event event) { }
        };
    }
}

