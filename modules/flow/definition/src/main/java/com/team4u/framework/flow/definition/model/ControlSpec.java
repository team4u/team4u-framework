package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 作用域级治理控制配置规范（Control Spec，如 timeout 10s { ... }、policy order.rate-limit { ... } 等）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class ControlSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    /** 控制类型。 */
    public enum ControlKind {
        POLICY,
        RETRY,
        TIMEOUT,
        NAMED,
        SCOPE
    }

    private final ControlKind kind;
    private final SymbolRef symbol;
    private final SymbolRef key;
    private final Object configuration;
    private final FlowSpec body;
    private final SourceSpan span;

    public ControlSpec(
            ControlKind kind,
            SymbolRef symbol,
            SymbolRef key,
            Object configuration,
            FlowSpec body,
            SourceSpan span) {
        this.kind = Objects.requireNonNull(kind, "control kind must not be null");
        this.symbol = symbol;
        this.key = key;
        if (configuration instanceof Map) {
            this.configuration = Collections.unmodifiableMap(new LinkedHashMap<Object, Object>((Map<?, ?>) configuration));
        } else {
            this.configuration = configuration;
        }
        this.body = Objects.requireNonNull(body, "body must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public static ControlSpec timeout(Duration duration, FlowSpec body, SourceSpan span) {
        return new ControlSpec(ControlKind.TIMEOUT, null, null, duration, body, span);
    }

    public static ControlSpec named(String label, FlowSpec body, SourceSpan span) {
        return new ControlSpec(ControlKind.NAMED, null, null, label, body, span);
    }

    public static ControlSpec scope(String scopeName, FlowSpec body, SourceSpan span) {
        return new ControlSpec(ControlKind.SCOPE, null, null, scopeName, body, span);
    }
}
