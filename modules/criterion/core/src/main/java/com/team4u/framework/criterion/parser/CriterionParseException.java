package com.team4u.framework.criterion.parser;

import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.parser.SourceSpan;
import lombok.Getter;

/**
 * 规则解析异常
 */
@Getter
public class CriterionParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 异常相关的表达式
     */
    private final String expression;

    /**
     * 异常发生的源码定位范围
     */
    private final SourceSpan span;

    public CriterionParseException(String message, String expression, SourceSpan span) {
        super(formatMessage(message, expression, span));
        this.expression = expression;
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public CriterionParseException(String message, SourceSpan span) {
        this(message, null, span);
    }

    public CriterionParseException(String message, String expression) {
        this(message, expression, SourceSpan.UNKNOWN);
    }

    public CriterionParseException(String message) {
        this(message, null, SourceSpan.UNKNOWN);
    }

    public SourceSpan span() {
        return span;
    }

    private static String formatMessage(String message, String expression, SourceSpan span) {
        if (span != null && span.known()) {
            if (expression != null) {
                return StringUtil.simpleFormat("{} (at {}:{}, expression: [{}])", message, span.startLine(), span.startColumn(), expression);
            }
            return StringUtil.simpleFormat("[{}] {}", span.format(), message);
        }
        if (expression != null) {
            return StringUtil.simpleFormat("{} (expression: [{}])", message, expression);
        }
        return message;
    }
}
