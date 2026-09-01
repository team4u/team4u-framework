package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 业务原子步骤配置规范（Step Spec）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class StepSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef operation;
    private final SymbolRef project;
    private final SymbolRef merge;
    private final List<ModifierSpec> modifiers;
    private final SourceSpan span;

    public StepSpec(
            SymbolRef operation,
            SymbolRef project,
            SymbolRef merge,
            List<ModifierSpec> modifiers,
            SourceSpan span) {
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.project = project;
        this.merge = merge;
        this.modifiers = modifiers != null
                ? Collections.unmodifiableList(new ArrayList<ModifierSpec>(modifiers))
                : Collections.<ModifierSpec>emptyList();
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
