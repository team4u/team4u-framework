package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Objects;

/**
 * 符号引用（Symbol Ref），表示 DSL 中对外部 Operation、Policy、Projector、Merger、Join 或 ResumePoint 的引用。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
public final class SymbolRef implements ProjectionSpec, MergeSpec, JoinSpec, Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final SourceSpan span;

    public SymbolRef(String id, SourceSpan span) {
        this.id = Objects.requireNonNull(id, "symbol id must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public static SymbolRef of(String id, SourceSpan span) {
        return new SymbolRef(id, span);
    }

    public static SymbolRef of(String id) {
        return new SymbolRef(id, SourceSpan.UNKNOWN);
    }

    @Override
    public String toString() {
        return id;
    }
}
