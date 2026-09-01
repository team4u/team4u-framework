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
 * 结构化并行配置规范（Parallel Spec）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class ParallelSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    private final List<BranchSpec> branches;
    private final SymbolRef join;
    private final SourceSpan span;

    public ParallelSpec(List<BranchSpec> branches, SymbolRef join, SourceSpan span) {
        this.branches = branches != null
                ? Collections.unmodifiableList(new ArrayList<BranchSpec>(branches))
                : Collections.<BranchSpec>emptyList();
        this.join = Objects.requireNonNull(join, "join symbol must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
