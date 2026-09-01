package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 重试治理修饰器规范（retry <retry-id> [{ ... }]）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class RetryModifierSpec implements ModifierSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef retry;
    private final Map<String, Object> configuration;
    private final SourceSpan span;

    public RetryModifierSpec(
            SymbolRef retry,
            Map<String, Object> configuration,
            SourceSpan span) {
        this.retry = Objects.requireNonNull(retry, "retry symbol must not be null");
        this.configuration = configuration != null
                ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(configuration))
                : Collections.<String, Object>emptyMap();
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
