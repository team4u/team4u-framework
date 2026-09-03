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

    private final MergeSpec merge;
    private final SourceSpan span;

    public MergeModifierSpec(MergeSpec merge, SourceSpan span) {
        this.merge = Objects.requireNonNull(merge, "merge must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public MergeModifierSpec(SymbolRef merger, SourceSpan span) {
        this(new SymbolMergeSpec(merger, span), span);
    }

    public SymbolRef merger() {
        return merge instanceof SymbolMergeSpec
                ? ((SymbolMergeSpec) merge).symbol()
                : null;
    }
}
