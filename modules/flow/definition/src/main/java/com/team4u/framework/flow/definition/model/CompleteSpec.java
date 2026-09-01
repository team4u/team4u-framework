package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 终态常量结果配置规范（Complete Spec，如 accepted、rejected、skipped、failed）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class CompleteSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    /** 终态种类。 */
    public enum CompleteKind {
        ACCEPTED,
        REJECTED,
        SKIPPED,
        FAILED
    }

    private final CompleteKind kind;
    private final String literal;
    private final SourceSpan span;

    public CompleteSpec(CompleteKind kind, String literal, SourceSpan span) {
        this.kind = Objects.requireNonNull(kind, "complete kind must not be null");
        this.literal = literal;
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public static CompleteSpec accepted(String literal, SourceSpan span) {
        return new CompleteSpec(CompleteKind.ACCEPTED, literal, span);
    }

    public static CompleteSpec rejected(String code, SourceSpan span) {
        return new CompleteSpec(CompleteKind.REJECTED, code, span);
    }

    public static CompleteSpec skipped(String code, SourceSpan span) {
        return new CompleteSpec(CompleteKind.SKIPPED, code, span);
    }

    public static CompleteSpec failed(String code, SourceSpan span) {
        return new CompleteSpec(CompleteKind.FAILED, code, span);
    }
}
