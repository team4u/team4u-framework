package com.team4u.framework.flow.definition.model;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.parser.SourceSpan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 属性路径表达式（Property Path），表示从根对象按层级访问属性的路径定义（如 {@code $.items}、{@code $.order.id}）。
 *
 * <p>V1 版本支持点号层级定位，段列表预先解析存储，避免运行时反复拆分字符串。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
public final class PropertyPath implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String expression;
    private final List<String> segments;
    private final SourceSpan span;

    public PropertyPath(String expression, List<String> segments, SourceSpan span) {
        this.expression = Objects.requireNonNull(expression, "expression must not be null");
        Objects.requireNonNull(segments, "segments must not be null");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Property path segments must not be empty");
        }
        for (String seg : segments) {
            if (seg == null || seg.isEmpty()) {
                throw new IllegalArgumentException("Property path segment must not be null or empty");
            }
        }
        String expected = "$." + String.join(".", segments);
        if (!expression.trim().equals(expected)) {
            throw new IllegalArgumentException("Property path expression '" + expression + "' does not match segments: " + segments);
        }
        this.segments = Collections.unmodifiableList(new ArrayList<String>(segments));
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public static PropertyPath parse(String expression) {
        return parse(expression, SourceSpan.UNKNOWN);
    }

    public static PropertyPath parse(String expression, SourceSpan span) {
        Objects.requireNonNull(expression, "expression must not be null");
        String trimmed = expression.trim();
        if (!trimmed.startsWith("$.")) {
            throw new FlowDiagnosticException(new Diagnostic(
                    DiagnosticCodes.INVALID_PROPERTY_PATH,
                    "Property path must start with '$.', got: " + expression,
                    span != null ? span : SourceSpan.UNKNOWN));
        }
        String pathBody = trimmed.substring(2);
        if (pathBody.isEmpty()) {
            throw new FlowDiagnosticException(new Diagnostic(
                    DiagnosticCodes.INVALID_PROPERTY_PATH,
                    "Property path cannot be empty after '$.'",
                    span != null ? span : SourceSpan.UNKNOWN));
        }
        String[] parts = pathBody.split("\\.", -1);
        List<String> segs = new ArrayList<String>();
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.INVALID_PROPERTY_PATH,
                        "Property path segments cannot be empty in: " + expression,
                        span != null ? span : SourceSpan.UNKNOWN));
            }
            segs.add(part);
        }
        return new PropertyPath(expression, segs, span);
    }

    public static PropertyPath of(String expression) {
        return parse(expression, SourceSpan.UNKNOWN);
    }

    @Override
    public String toString() {
        return expression;
    }
}
