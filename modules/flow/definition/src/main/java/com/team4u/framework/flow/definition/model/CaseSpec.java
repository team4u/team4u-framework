package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Objects;

/**
 * 路由条件匹配分支配置规范（Case Spec）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class CaseSpec implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String literalKey;
    private final FlowSpec branch;
    private final SourceSpan span;

    public CaseSpec(String literalKey, FlowSpec branch, SourceSpan span) {
        this.literalKey = Objects.requireNonNull(literalKey, "literalKey must not be null");
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
