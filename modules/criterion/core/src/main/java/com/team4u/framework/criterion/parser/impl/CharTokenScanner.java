package com.team4u.framework.criterion.parser.impl;

import com.team4u.framework.criterion.parser.CriterionParseException;
import com.team4u.framework.criterion.parser.token.Token;
import com.team4u.framework.criterion.parser.token.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于字符流状态机的分词器
 */
public class CharTokenScanner {
    /**
     * 待解析的表达式字符串
     */
    private final String expr;
    /**
     * 表达式长度
     */
    private final int length;
    /**
     * 当前扫描位置
     */
    private int pos = 0;

    /**
     * 构造分词器
     *
     * @param expression 待解析的表达式字符串，null 会被视为空字符串
     */
    public CharTokenScanner(String expression) {
        this.expr = expression == null ? "" : expression;
        this.length = this.expr.length();
    }

    /**
     * 执行词法分析，将表达式字符串转换为 Token 列表
     * <p>
     * 支持的 Token 类型：
     * <ul>
     * <li>STRING - 单引号包裹的字符串</li>
     * <li>NUMBER - 整数或小数</li>
     * <li>IDENTIFIER - 标识符（支持点号、竖线、连字符）</li>
     * <li>OPERATOR - 运算符（包括 && 和 ||）</li>
     * <li>DELIMITER - 分隔符（括号、方括号、逗号、冒号）</li>
     * </ul>
     *
     * @return Token 列表
     * @throws CriterionParseException 遇到非法字符或未闭合的字符串时抛出
     */
    public List<Token> scan() {
        List<Token> tokens = new ArrayList<>();
        while (pos < length) {
            char c = peek();

            if (Character.isWhitespace(c)) {
                pos++;
                continue;
            }

            int startPos = pos;
            if (c == '\'') {
                tokens.add(new Token(TokenType.STRING, readString(), startPos));
                continue;
            }
            if (isDelimiter(c)) {
                tokens.add(new Token(TokenType.DELIMITER, String.valueOf(consume()), startPos));
                continue;
            }
            if (isDigit(c) || ((c == '+' || c == '-') && isDigit(peekNext()))) {
                tokens.add(new Token(TokenType.NUMBER, readNumber(), startPos));
                continue;
            }
            if (isIdentifierStart(c)) {
                tokens.add(new Token(TokenType.IDENTIFIER, readIdentifier(), startPos));
                continue;
            }
            if (isOperatorChar(c) || c == '&' || c == '|') {
                tokens.add(new Token(TokenType.OPERATOR, readOperator(), startPos));
                continue;
            }

            throw new CriterionParseException("Unexpected character: '" + c + "'", expr, pos);
        }
        return tokens;
    }

    /**
     * 读取字符串字面量（单引号包裹）
     * <p>
     * 支持转义字符，以反斜杠开头
     *
     * @return 包含引号的完整字符串字面量
     * @throws CriterionParseException 字符串未闭合时抛出
     */
    private String readString() {
        int start = pos;
        consume();
        boolean closed = false;
        StringBuilder sb = new StringBuilder();
        sb.append('\'');
        while (pos < length) {
            char c = consume();
            if (c == '\\' && pos < length) {
                char next = consume();
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
            throw new CriterionParseException("Unclosed string literal", expr, start);
        }
        return sb.toString();
    }

    /**
     * 读取数字字面量
     * <p>
     * 支持正负号、整数和小数
     *
     * @return 数字字符串
     */
    private String readNumber() {
        int start = pos;
        consume();
        boolean hasDot = false;
        while (pos < length) {
            char c = peek();
            if (isDigit(c)) {
                consume();
            } else if (c == '.' && !hasDot && isDigit(peekNext())) {
                hasDot = true;
                consume();
            } else {
                break;
            }
        }
        return expr.substring(start, pos);
    }

    /**
     * 读取标识符
     * <p>
     * 标识符可包含字母、数字、下划线、美元符号，以及点号、竖线、连字符
     * 用于支持如 "a.b"、"name|alias"、"my-var" 等形式
     *
     * @return 标识符字符串
     */
    private String readIdentifier() {
        int start = pos;
        while (pos < length && isIdentifierPart(peek())) {
            consume();
        }
        return expr.substring(start, pos);
    }

    /**
     * 读取运算符
     * <p>
     * 特别处理 && 和 || 逻辑运算符（双字符）
     *
     * @return 运算符字符串
     */
    private String readOperator() {
        int start = pos;
        char c = peek();
        if ((c == '&' && peekNext() == '&') || (c == '|' && peekNext() == '|')) {
            pos += 2;
            return expr.substring(start, pos);
        }
        while (pos < length && isOperatorChar(peek())) {
            consume();
        }
        return expr.substring(start, pos);
    }

    /**
     * 查看当前字符，若已到末尾返回空字符
     */
    private char peek() {
        return pos < length ? expr.charAt(pos) : '\0';
    }

    /**
     * 查看下一个字符，若超出范围返回空字符
     */
    private char peekNext() {
        return pos + 1 < length ? expr.charAt(pos + 1) : '\0';
    }

    /**
     * 消费当前字符并推进位置，返回被消费的字符
     */
    private char consume() {
        return expr.charAt(pos++);
    }

    /**
     * 判断是否为数字字符 0-9
     */
    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * 判断是否为标识符起始字符（字母、下划线、美元符号）
     */
    private boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    /**
     * 判断是否为标识符组成部分（起始字符、数字、点号、竖线、连字符）
     */
    private boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || isDigit(c) || c == '.' || c == '|' || c == '-';
    }

    /**
     * 判断是否为分隔符（括号、方括号、逗号、冒号）
     */
    private boolean isDelimiter(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']' || c == ',' || c == ':';
    }

    /**
     * 判断是否为运算符字符（不包括 & 和 |，它们由 readOperator 单独处理）
     */
    private boolean isOperatorChar(char c) {
        return "!@#%^&?*-+=/;<.>~".indexOf(c) != -1;
    }
}
