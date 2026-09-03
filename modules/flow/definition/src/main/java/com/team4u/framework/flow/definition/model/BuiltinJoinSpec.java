package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * DSL 内置聚合策略规范（Built-in Join Spec，如 {@code join all}、{@code join first}、{@code join collect}、{@code join quorum <N>}）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class BuiltinJoinSpec implements JoinSpec {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        ALL,
        FIRST,
        COLLECT,
        QUORUM
    }

    private final Kind kind;
    private final int quorumRequired;
    private final SourceSpan span;

    public BuiltinJoinSpec(Kind kind, int quorumRequired, SourceSpan span) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.quorumRequired = quorumRequired;
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public static BuiltinJoinSpec all(SourceSpan span) {
        return new BuiltinJoinSpec(Kind.ALL, 0, span);
    }

    public static BuiltinJoinSpec first(SourceSpan span) {
        return new BuiltinJoinSpec(Kind.FIRST, 0, span);
    }

    public static BuiltinJoinSpec collect(SourceSpan span) {
        return new BuiltinJoinSpec(Kind.COLLECT, 0, span);
    }

    public static BuiltinJoinSpec quorum(int required, SourceSpan span) {
        return new BuiltinJoinSpec(Kind.QUORUM, required, span);
    }
}
