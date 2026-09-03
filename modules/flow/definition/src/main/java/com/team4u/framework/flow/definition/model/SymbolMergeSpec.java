package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 符号合并规范（Symbol Merge Spec，如 {@code merge order.mergeReservation}）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class SymbolMergeSpec implements MergeSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef symbol;
    private final SourceSpan span;

    public SymbolMergeSpec(SymbolRef symbol, SourceSpan span) {
        this.symbol = Objects.requireNonNull(symbol, "symbol must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public SymbolMergeSpec(SymbolRef symbol) {
        this(symbol, SourceSpan.UNKNOWN);
    }
}
