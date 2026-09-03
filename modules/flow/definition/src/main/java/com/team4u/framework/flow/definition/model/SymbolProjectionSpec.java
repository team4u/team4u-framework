package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 符号投影规范（Symbol Projection Spec，如 {@code project order.extractItem}）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class SymbolProjectionSpec implements ProjectionSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef symbol;
    private final SourceSpan span;

    public SymbolProjectionSpec(SymbolRef symbol, SourceSpan span) {
        this.symbol = Objects.requireNonNull(symbol, "symbol must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public SymbolProjectionSpec(SymbolRef symbol) {
        this(symbol, SourceSpan.UNKNOWN);
    }
}
