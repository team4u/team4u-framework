package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 属性路径合并规范（Property Merge Spec，如 {@code merge $.reservationId}）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class PropertyMergeSpec implements MergeSpec {
    private static final long serialVersionUID = 1L;

    private final PropertyPath path;
    private final SourceSpan span;

    public PropertyMergeSpec(PropertyPath path, SourceSpan span) {
        this.path = Objects.requireNonNull(path, "path must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public PropertyMergeSpec(PropertyPath path) {
        this(path, SourceSpan.UNKNOWN);
    }
}
