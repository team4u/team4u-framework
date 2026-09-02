package com.team4u.framework.flow.definition.diagnostic;

import com.team4u.framework.parser.SourceSpan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Objects;

/**
 * 流程定义与编译期诊断信息（Diagnostic）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
public final class Diagnostic implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final String message;
    private final SourceSpan span;
    private final String path;

    public Diagnostic(String code, String message, SourceSpan span, String path) {
        this.code = Objects.requireNonNull(code, "diagnostic code must not be null");
        this.message = Objects.requireNonNull(message, "diagnostic message must not be null");
        this.span = span != null ? span : SourceSpan.UNKNOWN;
        this.path = path;
    }

    public Diagnostic(String code, String message, SourceSpan span) {
        this(code, message, span, null);
    }

    public Diagnostic(String code, String message) {
        this(code, message, SourceSpan.UNKNOWN, null);
    }

    /**
     * 格式化输出诊断信息，例如：
     * "order.flow:18:9: [TYPE_MISMATCH] Expected: PaymentRequest, Actual: Order"
     *
     * @return 格式化诊断文本
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        if (span != null && span != SourceSpan.UNKNOWN) {
            sb.append(span.format()).append(": ");
        }
        sb.append("[").append(code).append("] ");
        if (path != null && !path.isEmpty()) {
            sb.append("(").append(path).append(") ");
        }
        sb.append(message);
        return sb.toString();
    }

    @Override
    public String toString() {
        return format();
    }
}
