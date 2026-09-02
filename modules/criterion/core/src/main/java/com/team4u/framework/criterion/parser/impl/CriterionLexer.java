package com.team4u.framework.criterion.parser.impl;

import com.team4u.framework.criterion.parser.CriterionParseException;
import com.team4u.framework.criterion.parser.token.Token;
import com.team4u.framework.criterion.parser.token.TokenType;
import com.team4u.framework.parser.CharCursor;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于字符游标的规则词法分析器（Criterion Lexer）。
 *
 * <p>利用 {@link CharCursor} 将输入表达式解析为带有精确 {@link com.team4u.framework.parser.SourceSpan} 的 {@link Token} 序列。</p>
 *
 * @author jay.wu
 */
public class CriterionLexer {

    private final String expr;
    private final CharCursor cursor;

    /**
     * 构造分词器。
     *
     * @param expression 待解析的表达式字符串，null 会被视为空字符串
     * @param sourceName 源码标识或资源路径（可为 null）
     */
    public CriterionLexer(String expression, String sourceName) {
        this.expr = expression == null ? "" : expression;
        this.cursor = new CharCursor(this.expr, sourceName);
    }

    public CriterionLexer(String expression) {
        this(expression, null);
    }

    /**
     * 执行词法分析，将表达式字符串转换为 Token 列表。
     *
     * @return Token 列表
     * @throws CriterionParseException 遇到非法字符或未闭合的字符串时抛出
     */
    public List<Token> scan() {
        List<Token> tokens = new ArrayList<Token>();
        while (cursor.hasNext()) {
            char c = cursor.peek();

            if (Character.isWhitespace(c)) {
                cursor.advance();
                continue;
            }

            CharCursor.Mark mark = cursor.mark();
            if (c == '\'') {
                String val = readString(mark);
                tokens.add(new Token(TokenType.STRING, val, cursor.spanFrom(mark)));
                continue;
            }
            if (isDelimiter(c)) {
                cursor.advance();
                tokens.add(new Token(TokenType.DELIMITER, String.valueOf(c), cursor.spanFrom(mark)));
                continue;
            }
            if (isDigit(c) || ((c == '+' || c == '-') && cursor.has(1) && isDigit(cursor.peek(1)))) {
                String num = readNumber();
                tokens.add(new Token(TokenType.NUMBER, num, cursor.spanFrom(mark)));
                continue;
            }
            if (isIdentifierStart(c)) {
                String id = readIdentifier();
                tokens.add(new Token(TokenType.IDENTIFIER, id, cursor.spanFrom(mark)));
                continue;
            }
            if (isOperatorChar(c) || c == '&' || c == '|') {
                String op = readOperator();
                tokens.add(new Token(TokenType.OPERATOR, op, cursor.spanFrom(mark)));
                continue;
            }

            cursor.advance();
            throw new CriterionParseException("Unexpected character: '" + c + "'", expr, cursor.spanFrom(mark));
        }
        return tokens;
    }

    private String readString(CharCursor.Mark mark) {
        cursor.advance(); // 跳过起始单引号
        boolean closed = false;
        StringBuilder sb = new StringBuilder();
        sb.append('\'');
        while (cursor.hasNext()) {
            char c = cursor.advance();
            if (c == '\\' && cursor.hasNext()) {
                char next = cursor.advance();
                if (next == '\'' || next == '\\') {
                    sb.append(next);
                } else {
                    sb.append('\\').append(next);
                }
            } else if (c == '\'') {
                sb.append('\'');
                closed = true;
                break;
            } else {
                sb.append(c);
            }
        }
        if (!closed) {
            throw new CriterionParseException("Unclosed string literal", expr, cursor.spanFrom(mark));
        }
        return sb.toString();
    }

    private String readNumber() {
        CharCursor.Mark start = cursor.mark();
        cursor.advance();
        boolean hasDot = false;
        while (cursor.hasNext()) {
            char c = cursor.peek();
            if (isDigit(c)) {
                cursor.advance();
            } else if (c == '.' && !hasDot && cursor.has(1) && isDigit(cursor.peek(1))) {
                hasDot = true;
                cursor.advance();
            } else {
                break;
            }
        }
        return expr.substring(start.offset(), cursor.offset());
    }

    private String readIdentifier() {
        CharCursor.Mark start = cursor.mark();
        while (cursor.hasNext() && isIdentifierPart(cursor.peek())) {
            cursor.advance();
        }
        return expr.substring(start.offset(), cursor.offset());
    }

    private String readOperator() {
        CharCursor.Mark start = cursor.mark();
        char c = cursor.peek();
        if ((c == '&' && cursor.has(1) && cursor.peek(1) == '&') || (c == '|' && cursor.has(1) && cursor.peek(1) == '|')) {
            cursor.advance();
            cursor.advance();
            return expr.substring(start.offset(), cursor.offset());
        }
        while (cursor.hasNext() && isOperatorChar(cursor.peek())) {
            cursor.advance();
        }
        return expr.substring(start.offset(), cursor.offset());
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    private boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || isDigit(c) || c == '.' || c == '|' || c == '-';
    }

    private boolean isDelimiter(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']' || c == ',' || c == ':';
    }

    private boolean isOperatorChar(char c) {
        return "!@#%^&?*-+=/;<.>~".indexOf(c) != -1;
    }
}
