package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 策略治理修饰器规范（policy <policy-id> [key <key-id>] [{ ... }]）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class PolicyModifierSpec implements ModifierSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef policy;
    private final SymbolRef key;
    private final Map<String, Object> configuration;
    private final SourceSpan span;

    public PolicyModifierSpec(
            SymbolRef policy,
            SymbolRef key,
            Map<String, Object> configuration,
            SourceSpan span) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.key = key;
        this.configuration = configuration != null
                ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(configuration))
                : Collections.<String, Object>emptyMap();
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
