package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 结果合并修饰器规范（merge <merger-id>）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class MergeModifierSpec implements ModifierSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef merger;
    private final SourceSpan span;

    public MergeModifierSpec(SymbolRef merger, SourceSpan span) {
        this.merger = Objects.requireNonNull(merger, "merger must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
