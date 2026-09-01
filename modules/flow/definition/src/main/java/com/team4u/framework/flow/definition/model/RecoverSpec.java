package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 失败降级补偿配置规范（Recover Spec）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class RecoverSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    private final FlowSpec body;
    private final FlowSpec onFailure;
    private final SourceSpan span;

    public RecoverSpec(FlowSpec body, FlowSpec onFailure, SourceSpan span) {
        this.body = Objects.requireNonNull(body, "body must not be null");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
