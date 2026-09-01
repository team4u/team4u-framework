package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 可选步骤修饰器规范（optional），Skipped 时不终止流水线而是透传原值。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class OptionalModifierSpec implements ModifierSpec {
    private static final long serialVersionUID = 1L;

    private final SourceSpan span;

    public OptionalModifierSpec(SourceSpan span) {
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
