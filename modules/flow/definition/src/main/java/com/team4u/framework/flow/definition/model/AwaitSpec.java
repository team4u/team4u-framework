package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 异步挂起等待配置规范（Await Spec）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class AwaitSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef resumePoint;
    private final SourceSpan span;

    public AwaitSpec(SymbolRef resumePoint, SourceSpan span) {
        this.resumePoint = Objects.requireNonNull(resumePoint, "resumePoint must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
