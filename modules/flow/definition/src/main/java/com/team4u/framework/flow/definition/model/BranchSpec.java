package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Objects;

/**
 * 并行分支配置规范（Branch Spec）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class BranchSpec implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final FlowSpec flow;
    private final SourceSpan span;

    public BranchSpec(String name, FlowSpec flow, SourceSpan span) {
        this.name = Objects.requireNonNull(name, "branch name must not be null");
        this.flow = Objects.requireNonNull(flow, "branch flow must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
