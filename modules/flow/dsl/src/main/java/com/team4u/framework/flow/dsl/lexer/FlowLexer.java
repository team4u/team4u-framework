package com.team4u.framework.flow.dsl.lexer;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.type.TypeCodecs;
import com.team4u.framework.parser.CharCursor;
import com.team4u.framework.parser.SourceSpan;

import java.time.Duration;
import java.util.*;

/**
 * 流程 DSL 词法分析器（Flow Lexer）。
 *
 * <p>基于 {@link CharCursor} 将 DSL 源码字符串扫描为带有精确 {@link SourceSpan} 的 {@link Token} 序列。</p>
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

    private final CharCursor cursor;

    public FlowLexer(String source, String sourceName) {
        this.cursor = new CharCursor(source, sourceName);
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

        if (!cursor.hasNext()) {
            CharCursor.Mark mark = cursor.mark();
            return new Token(TokenType.EOF, "<EOF>", cursor.spanFrom(mark));
        }

        CharCursor.Mark start = cursor.mark();
        char ch = cursor.peek();

        // 标点符号
        if (ch == '{') {
            cursor.advance();
            return new Token(TokenType.LBRACE, "{", cursor.spanFrom(start));
        } else if (ch == '}') {
            cursor.advance();
            return new Token(TokenType.RBRACE, "}", cursor.spanFrom(start));
        } else if (ch == ':') {
            cursor.advance();
            return new Token(TokenType.COLON, ":", cursor.spanFrom(start));
        } else if (ch == ',') {
            cursor.advance();
            return new Token(TokenType.COMMA, ",", cursor.spanFrom(start));
        } else if (ch == '=') {
            cursor.advance();
            return new Token(TokenType.EQUALS, "=", cursor.spanFrom(start));
        }

        // 字符串字面量
        if (ch == '"' || ch == '\'') {
            return scanString(start, ch);
        }

        // 数字与时间长度字面量（如 1s, 500ms, 100, 3.14）
        if (Character.isDigit(ch)) {
            return scanNumberOrDuration(start);
        }

        // 标识符与关键字
        if (isIdentifierStart(ch)) {
            return scanIdentifierOrKeyword(start);
        }

        cursor.advance();
        SourceSpan span = cursor.spanFrom(start);
        throw new FlowDiagnosticException(new Diagnostic(
                DiagnosticCodes.DSL_SYNTAX_ERROR,
                "Unexpected character: '" + ch + "'",
                span));
    }

    private Token scanString(CharCursor.Mark start, char quoteChar) {
        cursor.advance(); // 跳过起始引号
        StringBuilder sb = new StringBuilder();

        while (cursor.hasNext()) {
            char ch = cursor.peek();
            if (ch == quoteChar) {
                cursor.advance(); // 跳过结束引号
                SourceSpan span = cursor.spanFrom(start);
                return new Token(TokenType.STRING, sb.toString(), span, sb.toString());
            } else if (ch == '\\') {
                cursor.advance();
                if (!cursor.hasNext()) {
                    break;
                }
                char escape = cursor.advance();
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
                cursor.advance();
                sb.append('\n');
            } else {
                sb.append(cursor.advance());
            }
        }

        SourceSpan span = cursor.spanFrom(start);
        throw new FlowDiagnosticException(new Diagnostic(
                DiagnosticCodes.DSL_SYNTAX_ERROR,
                "Unterminated string literal",
                span));
    }

    private Token scanNumberOrDuration(CharCursor.Mark start) {
        StringBuilder numBuf = new StringBuilder();
        boolean hasDot = false;

        while (cursor.hasNext()) {
            char ch = cursor.peek();
            if (Character.isDigit(ch)) {
                numBuf.append(cursor.advance());
            } else if (ch == '.' && !hasDot && cursor.has(1) && Character.isDigit(cursor.peek(1))) {
                hasDot = true;
                numBuf.append(cursor.advance());
            } else {
                break;
            }
        }

        // 检查是否紧随时间单位（如 ns, us, ms, s, m, h, d）
        if (cursor.hasNext() && Character.isLetter(cursor.peek())) {
            StringBuilder unitBuf = new StringBuilder();
            while (cursor.hasNext() && Character.isLetter(cursor.peek())) {
                unitBuf.append(cursor.advance());
            }
            String durationText = numBuf.toString() + unitBuf.toString();
            SourceSpan span = cursor.spanFrom(start);
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

        String numText = numBuf.toString();
        SourceSpan span = cursor.spanFrom(start);
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

    private Token scanIdentifierOrKeyword(CharCursor.Mark start) {
        StringBuilder sb = new StringBuilder();
        while (cursor.hasNext() && isIdentifierPart(cursor.peek())) {
            sb.append(cursor.advance());
        }

        String text = sb.toString();
        SourceSpan span = cursor.spanFrom(start);

        TokenType keywordType = KEYWORDS.get(text);
        if (keywordType != null) {
            return new Token(keywordType, text, span);
        }

        return new Token(TokenType.IDENTIFIER, text, span, text);
    }

    private boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_' || ch == '$';
    }

    private boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '.' || ch == '-' || ch == '$';
    }

    private boolean isLineBreak(char ch) {
        return ch == '\r' || ch == '\n';
    }

    private void skipWhitespaceAndComments() {
        while (cursor.hasNext()) {
            char ch = cursor.peek();

            // 空白字符
            if (Character.isWhitespace(ch)) {
                cursor.advance();
                continue;
            }

            // 单行注释 #
            if (ch == '#') {
                while (cursor.hasNext() && !isLineBreak(cursor.peek())) {
                    cursor.advance();
                }
                continue;
            }

            // // 单行注释 或 /* 多行注释 */
            if (ch == '/' && cursor.has(1)) {
                char next = cursor.peek(1);
                if (next == '/') {
                    cursor.advance();
                    cursor.advance();
                    while (cursor.hasNext() && !isLineBreak(cursor.peek())) {
                        cursor.advance();
                    }
                    continue;
                } else if (next == '*') {
                    CharCursor.Mark commentStart = cursor.mark();
                    cursor.advance();
                    cursor.advance();
                    boolean closed = false;
                    while (cursor.hasNext()) {
                        if (cursor.peek() == '*' && cursor.has(1) && cursor.peek(1) == '/') {
                            cursor.advance();
                            cursor.advance();
                            closed = true;
                            break;
                        }
                        cursor.advance();
                    }
                    if (!closed) {
                        SourceSpan span = cursor.spanFrom(commentStart);
                        throw new FlowDiagnosticException(new Diagnostic(
                                DiagnosticCodes.DSL_SYNTAX_ERROR,
                                "Unterminated block comment",
                                span));
                    }
                    continue;
                }
            }

            break;
        }
    }
}
