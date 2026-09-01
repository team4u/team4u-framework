package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 条件路由配置规范（Route Spec）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class RouteSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef selector;
    private final List<CaseSpec> cases;
    private final FlowSpec otherwise;
    private final SourceSpan span;

    public RouteSpec(
            SymbolRef selector,
            List<CaseSpec> cases,
            FlowSpec otherwise,
            SourceSpan span) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.cases = cases != null
                ? Collections.unmodifiableList(new ArrayList<CaseSpec>(cases))
                : Collections.<CaseSpec>emptyList();
        this.otherwise = otherwise;
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }
}
