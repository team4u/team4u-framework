package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 命名标签修饰器规范（named <label>）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class NamedModifierSpec implements ModifierSpec {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final SourceSpan span;

    public NamedModifierSpec(String name, SourceSpan span) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
