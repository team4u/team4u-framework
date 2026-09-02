package com.team4u.framework.flow.dsl.lexer;

import com.team4u.framework.parser.SourceSpan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 词法分析记号（Token）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
public final class Token implements Serializable {
    private static final long serialVersionUID = 1L;

    private final TokenType type;
    private final String text;
    private final SourceSpan span;
    private final Object value;

    public Token(TokenType type, String text, SourceSpan span, Object value) {
        this.type = type;
        this.text = text;
        this.span = span != null ? span : SourceSpan.UNKNOWN;
        this.value = value;
    }

    public Token(TokenType type, String text, SourceSpan span) {
        this(type, text, span, null);
    }

    public TokenType type() {
        return type;
    }

    public String text() {
        return text;
    }

    public SourceSpan span() {
        return span;
    }

    public Object value() {
        return value;
    }

    @Override
    public String toString() {
        return type + "('" + text + "')@" + span.format();
    }
}
