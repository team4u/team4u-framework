package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 外部符号聚合规范（Symbol Join Spec）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class SymbolJoinSpec implements JoinSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef symbol;
    private final SourceSpan span;

    public SymbolJoinSpec(SymbolRef symbol, SourceSpan span) {
        this.symbol = Objects.requireNonNull(symbol, "symbol must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public SymbolJoinSpec(SymbolRef symbol) {
        this(symbol, SourceSpan.UNKNOWN);
    }
}
