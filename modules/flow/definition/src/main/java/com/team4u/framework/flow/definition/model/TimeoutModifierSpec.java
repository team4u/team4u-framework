package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Duration;
import java.util.Objects;

/**
 * 超时治理修饰器规范（timeout <duration>）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class TimeoutModifierSpec implements ModifierSpec {
    private static final long serialVersionUID = 1L;

    private final Duration duration;
    private final SourceSpan span;

    public TimeoutModifierSpec(Duration duration, SourceSpan span) {
        this.duration = Objects.requireNonNull(duration, "duration must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
