package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 首选适用多路尝试配置规范（First-Applicable Spec）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class FirstApplicableSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    private final List<FlowSpec> branches;
    private final SourceSpan span;

    public FirstApplicableSpec(List<FlowSpec> branches, SourceSpan span) {
        this.branches = branches != null
                ? Collections.unmodifiableList(new ArrayList<FlowSpec>(branches))
                : Collections.<FlowSpec>emptyList();
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
