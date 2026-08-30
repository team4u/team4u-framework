package com.team4u.framework.flow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 同步执行观察者。回调中抛出的运行时异常被框架隔离，不会影响 flow 行为。
 */
@FunctionalInterface
public interface FlowObserver {
    enum Type {
        FLOW_STARTED, FLOW_COMPLETED, FLOW_SUSPENDED, FLOW_CANCELLED,
        NODE_STARTED, NODE_COMPLETED, ROUTE_SELECTED, FALLBACK_SELECTED,
        POLICY_BEFORE, POLICY_AFTER, POLICY_WAITING,
        PARALLEL_STARTED, PARALLEL_BRANCH_COMPLETED, PARALLEL_JOINED
    }

    final class Event {
        private final Type type;
        private final Instant at;
        private final Metadata metadata;
        private final NodeDescriptor descriptor;
        private final Map<String, String> attributes;

        public Event(Type type, Instant at, Metadata metadata,
                     NodeDescriptor descriptor, Map<String, String> attributes) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.at = Objects.requireNonNull(at, "at must not be null");
            this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(attributes, "attributes must not be null");
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
        }

        public Type type() {
            return type;
        }

        public Instant at() {
            return at;
        }

        public Metadata metadata() {
            return metadata;
        }

        public NodeDescriptor descriptor() {
            return descriptor;
        }

        public Map<String, String> attributes() {
            return attributes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Event event = (Event) o;
            return type == event.type
                    && at.equals(event.at)
                    && metadata.equals(event.metadata)
                    && descriptor.equals(event.descriptor)
                    && attributes.equals(event.attributes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, at, metadata, descriptor, attributes);
        }

        @Override
        public String toString() {
            return "Event[type=" + type + ", at=" + at + ", metadata=" + metadata
                    + ", descriptor=" + descriptor + ", attributes=" + attributes + "]";
        }
    }

    void onEvent(Event event);

    /** 不处理任何事件的空观察者。 */
    static FlowObserver noop() {
        return new FlowObserver() {
            @Override
            public void onEvent(Event event) {
            }
        };
    }

    /** 按顺序广播给全部 observers；单个 observer 抛出的异常被吞掉，不影响后续与执行。 */
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
