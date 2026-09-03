package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

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
    private final JoinSpec joinSpec;
    private final SourceSpan span;

    public ParallelSpec(List<BranchSpec> branches, JoinSpec joinSpec, SourceSpan span) {
        this.branches = branches != null
                ? Collections.unmodifiableList(new ArrayList<BranchSpec>(branches))
                : Collections.<BranchSpec>emptyList();
        this.joinSpec = Objects.requireNonNull(joinSpec, "join spec must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public ParallelSpec(List<BranchSpec> branches, SymbolRef join, SourceSpan span) {
        this(branches, new SymbolJoinSpec(join, span), span);
    }

    public SymbolRef join() {
        if (joinSpec instanceof SymbolRef) {
            return (SymbolRef) joinSpec;
        }
        return joinSpec instanceof SymbolJoinSpec
                ? ((SymbolJoinSpec) joinSpec).symbol()
                : null;
    }
}
