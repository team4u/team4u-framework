package com.team4u.framework.flow.dsl.lexer;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.SourceSpan;
import com.team4u.framework.flow.definition.type.TypeCodecs;

import java.time.Duration;
import java.util.*;

/**
 * 流程 DSL 词法分析器（Flow Lexer）。
 *
 * <p>将 DSL 源码字符串扫描为带有精确 {@link SourceSpan}（行号、列号）的 {@link Token} 序列。</p>
 *
 * @author jay.wu
 */
public final class FlowLexer {

    private static final Map<String, TokenType> KEYWORDS;

    static {
        Map<String, TokenType> map = new HashMap<String, TokenType>();
        for (TokenType type : TokenType.values()) {
            if (type.keyword() != null) {
                map.put(type.keyword(), type);
            }
        }
        KEYWORDS = Collections.unmodifiableMap(map);
    }

    private final String source;
    private final String sourceName;
    private final int length;
    private int index = 0;
    private int line = 1;
    private int column = 1;

    public FlowLexer(String source, String sourceName) {
        this.source = source != null ? source : "";
        this.sourceName = sourceName;
        this.length = this.source.length();
    }

    public FlowLexer(String source) {
        this(source, null);
    }

    /**
     * 将整个输入扫描为 Token 列表。
     *
     * @return Token 列表
     */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<Token>();
        while (true) {
            Token token = nextToken();
            tokens.add(token);
            if (token.type() == TokenType.EOF) {
                break;
            }
        }
        return tokens;
    }

    /**
     * 读取下一个 Token。
     *
     * @return Token 实例
     */
    public Token nextToken() {
        skipWhitespaceAndComments();

        if (index >= length) {
            SourceSpan span = new SourceSpan(sourceName, line, column, line, column);
            return new Token(TokenType.EOF, "<EOF>", span);
        }

        int startLine = line;
        int startColumn = column;
        char ch = source.charAt(index);

        // 标点符号
        if (ch == '{') {
            advance();
            return new Token(TokenType.LBRACE, "{", span(startLine, startColumn));
        } else if (ch == '}') {
            advance();
            return new Token(TokenType.RBRACE, "}", span(startLine, startColumn));
        } else if (ch == ':') {
            advance();
            return new Token(TokenType.COLON, ":", span(startLine, startColumn));
        } else if (ch == ',') {
            advance();
            return new Token(TokenType.COMMA, ",", span(startLine, startColumn));
        } else if (ch == '=') {
            advance();
            return new Token(TokenType.EQUALS, "=", span(startLine, startColumn));
        }

        // 字符串字面量
        if (ch == '"' || ch == '\'') {
            return scanString(startLine, startColumn, ch);
        }

        // 数字与时间长度字面量（如 1s, 500ms, 100, 3.14）
        if (Character.isDigit(ch)) {
            return scanNumberOrDuration(startLine, startColumn);
        }

        // 标识符与关键字
        if (isIdentifierStart(ch)) {
            return scanIdentifierOrKeyword(startLine, startColumn);
        }

        advance();
        SourceSpan span = span(startLine, startColumn);
        throw new FlowDiagnosticException(new Diagnostic(
                DiagnosticCodes.DSL_SYNTAX_ERROR,
                "Unexpected character: '" + ch + "'",
                span));
    }

    private Token scanString(int startLine, int startColumn, char quoteChar) {
        advance(); // 跳过起始引号
        StringBuilder sb = new StringBuilder();

        while (index < length) {
            char ch = source.charAt(index);
            if (ch == quoteChar) {
                advance(); // 跳过结束引号
                SourceSpan span = span(startLine, startColumn);
                return new Token(TokenType.STRING, sb.toString(), span, sb.toString());
            } else if (ch == '\\') {
                advance();
                if (index >= length) {
                    break;
                }
                char escape = source.charAt(index);
                advance();
                if (escape == 'n') sb.append('\n');
                else if (escape == 't') sb.append('\t');
                else if (escape == 'r') sb.append('\r');
                else if (escape == 'b') sb.append('\b');
                else if (escape == 'f') sb.append('\f');
                else if (escape == '"') sb.append('"');
                else if (escape == '\'') sb.append('\'');
                else if (escape == '\\') sb.append('\\');
                else sb.append(escape);
            } else if (ch == '\n') {
                advance();
                sb.append('\n');
            } else {
                advance();
                sb.append(ch);
            }
        }

        SourceSpan span = span(startLine, startColumn);
        throw new FlowDiagnosticException(new Diagnostic(
                DiagnosticCodes.DSL_SYNTAX_ERROR,
                "Unterminated string literal",
                span));
    }

    private Token scanNumberOrDuration(int startLine, int startColumn) {
        int startIndex = index;
        boolean hasDot = false;

        while (index < length) {
            char ch = source.charAt(index);
            if (Character.isDigit(ch)) {
                advance();
            } else if (ch == '.' && !hasDot && index + 1 < length && Character.isDigit(source.charAt(index + 1))) {
                hasDot = true;
                advance();
            } else {
                break;
            }
        }

        // 检查是否紧随时间单位（如 ns, us, ms, s, m, h, d）
        if (index < length && Character.isLetter(source.charAt(index))) {
            int unitStart = index;
            while (index < length && Character.isLetter(source.charAt(index))) {
                advance();
            }
            String durationText = source.substring(startIndex, index);
            SourceSpan span = span(startLine, startColumn);
            try {
                Duration duration = TypeCodecs.parseDuration(durationText);
                return new Token(TokenType.DURATION, durationText, span, duration);
            } catch (Exception ex) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.DSL_SYNTAX_ERROR,
                        "Invalid duration literal: " + durationText,
                        span));
            }
        }

        String numText = source.substring(startIndex, index);
        SourceSpan span = span(startLine, startColumn);
        Number value;
        if (hasDot) {
            value = Double.parseDouble(numText);
        } else {
            try {
                value = Long.parseLong(numText);
            } catch (NumberFormatException ex) {
                value = Double.parseDouble(numText);
            }
        }
        return new Token(TokenType.NUMBER, numText, span, value);
    }

    private Token scanIdentifierOrKeyword(int startLine, int startColumn) {
        int startIndex = index;
        while (index < length) {
            char ch = source.charAt(index);
            if (isIdentifierPart(ch)) {
                advance();
            } else {
                break;
            }
        }

        String text = source.substring(startIndex, index);
        SourceSpan span = span(startLine, startColumn);

        TokenType keywordType = KEYWORDS.get(text);
        if (keywordType != null) {
            return new Token(keywordType, text, span);
        }

        return new Token(TokenType.IDENTIFIER, text, span, text);
    }

    private boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '.' || ch == '-';
    }

    private void skipWhitespaceAndComments() {
        while (index < length) {
            char ch = source.charAt(index);

            // 空白字符
            if (Character.isWhitespace(ch)) {
                advance();
                continue;
            }

            // 单行注释 #
            if (ch == '#') {
                while (index < length && source.charAt(index) != '\n') {
                    advance();
                }
                continue;
            }

            // // 单行注释 或 /* 多行注释 */
            if (ch == '/' && index + 1 < length) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    advance();
                    advance();
                    while (index < length && source.charAt(index) != '\n') {
                        advance();
                    }
                    continue;
                } else if (next == '*') {
                    advance();
                    advance();
                    while (index + 1 < length) {
                        if (source.charAt(index) == '*' && source.charAt(index + 1) == '/') {
                            advance();
                            advance();
                            break;
                        }
                        advance();
                    }
                    continue;
                }
            }

            break;
        }
    }

    private void advance() {
        if (index < length) {
            char ch = source.charAt(index);
            index++;
            if (ch == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
    }

    private SourceSpan span(int startLine, int startColumn) {
        return new SourceSpan(sourceName, startLine, startColumn, line, column);
    }
}
