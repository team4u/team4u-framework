package com.team4u.framework.criterion.parser.token;

import com.team4u.framework.parser.SourceSpan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 词法单元实体（不可变）
 */
@Getter
@ToString
@EqualsAndHashCode
public final class Token implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 类型
     */
    private final TokenType type;
    /**
     * 原始字符串内容
     */
    private final String value;
    /**
     * 源码空间范围定位
     */
    private final SourceSpan span;

    public Token(TokenType type, String value, SourceSpan span) {
        this.type = type;
        this.value = value;
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public Token(TokenType type, String value) {
        this(type, value, SourceSpan.UNKNOWN);
    }

    public TokenType type() {
        return type;
    }

    public String value() {
        return value;
    }

    public SourceSpan span() {
        return span;
    }
}
