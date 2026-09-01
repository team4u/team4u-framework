package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 顺序流水线配置规范（Sequence Spec），scopeName 标记具名作用域边界。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class SequenceSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    private final List<FlowSpec> elements;
    private final String scopeName;
    private final SourceSpan span;

    public SequenceSpec(List<FlowSpec> elements, String scopeName, SourceSpan span) {
        this.elements = elements != null
                ? Collections.unmodifiableList(new ArrayList<FlowSpec>(elements))
                : Collections.<FlowSpec>emptyList();
        this.scopeName = scopeName;
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public SequenceSpec(List<FlowSpec> elements, SourceSpan span) {
        this(elements, null, span);
    }
}
