package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 入参提取投影修饰器规范（project <projector-id>）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class ProjectModifierSpec implements ModifierSpec {
    private static final long serialVersionUID = 1L;

    private final ProjectionSpec projection;
    private final SourceSpan span;

    public ProjectModifierSpec(ProjectionSpec projection, SourceSpan span) {
        this.projection = Objects.requireNonNull(projection, "projection must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public ProjectModifierSpec(SymbolRef projector, SourceSpan span) {
        this((ProjectionSpec) projector, span);
    }

    public SymbolRef projector() {
        if (projection instanceof SymbolRef) {
            return (SymbolRef) projection;
        }
        return projection instanceof SymbolProjectionSpec
                ? ((SymbolProjectionSpec) projection).symbol()
                : null;
    }
}
