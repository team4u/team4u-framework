package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Metadata;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Observer for Durable-specific checkpoint and restore events. */
@FunctionalInterface
public interface DurableObserver {
    enum Type { CHECKPOINT_COMMITTED, CHECKPOINT_RESTORED, RESUME_SIGNAL_PERSISTED }

    final class Event {
        private final Type type;
        private final Instant at;
        private final Metadata metadata;
        private final long revision;
        private final DurableLifecycle lifecycle;
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

        public Type type() { return type; }
        public Instant at() { return at; }
        public Metadata metadata() { return metadata; }
        public long revision() { return revision; }
        public DurableLifecycle lifecycle() { return lifecycle; }
        public Map<String, String> attributes() { return attributes; }
    }

    void onEvent(Event event);

    static DurableObserver noop() {
        return new DurableObserver() {
            @Override public void onEvent(Event event) { }
        };
    }
}
