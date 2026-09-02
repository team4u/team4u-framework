package com.team4u.framework.flow.dsl;

import com.team4u.framework.flow.dsl.lexer.FlowLexer;
import com.team4u.framework.flow.dsl.lexer.Token;
import com.team4u.framework.flow.dsl.lexer.TokenType;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.List;

public class FlowLexerTest {

    @Test
    public void testTokenizeKeywordsAndIdentifiers() {
        String dsl = "# Comment\n" +
                "// Another comment\n" +
                "/* Block\n" +
                "comment */\n" +
                "schema 1\n" +
                "flow order.create version 7 {\n" +
                "    step order.validate\n" +
                "    timeout 3s\n" +
                "    policy payment.rate-limit key order.userId\n" +
                "}";

        FlowLexer lexer = new FlowLexer(dsl, "order.flow");
        List<Token> tokens = lexer.tokenize();

        Assert.assertFalse(tokens.isEmpty());
        Assert.assertEquals(TokenType.SCHEMA, tokens.get(0).type());
        Assert.assertEquals(TokenType.NUMBER, tokens.get(1).type());
        Assert.assertEquals(TokenType.FLOW, tokens.get(2).type());
        Assert.assertEquals(TokenType.IDENTIFIER, tokens.get(3).type());
        Assert.assertEquals("order.create", tokens.get(3).text());
        Assert.assertEquals(TokenType.VERSION, tokens.get(4).type());
        Assert.assertEquals(TokenType.NUMBER, tokens.get(5).type());
        Assert.assertEquals("7", tokens.get(5).text());
        Assert.assertEquals(TokenType.LBRACE, tokens.get(6).type());
    }

    @Test
    public void testDurationTokens() {
        String dsl = "timeout 500ms timeout 3s timeout 10m";
        FlowLexer lexer = new FlowLexer(dsl);
        List<Token> tokens = lexer.tokenize();

        Assert.assertEquals(TokenType.TIMEOUT, tokens.get(0).type());
        Assert.assertEquals(TokenType.DURATION, tokens.get(1).type());
        Assert.assertEquals(Duration.ofMillis(500), tokens.get(1).value());

        Assert.assertEquals(TokenType.TIMEOUT, tokens.get(2).type());
        Assert.assertEquals(TokenType.DURATION, tokens.get(3).type());
        Assert.assertEquals(Duration.ofSeconds(3), tokens.get(3).value());

        Assert.assertEquals(TokenType.TIMEOUT, tokens.get(4).type());
        Assert.assertEquals(TokenType.DURATION, tokens.get(5).type());
        Assert.assertEquals(Duration.ofMinutes(10), tokens.get(5).value());
    }

    @Test
    public void testStringLiteralWithEscapes() {
        String dsl = "named \"Hello \\\"World\\\" \\n \\t\"";
        FlowLexer lexer = new FlowLexer(dsl);
        List<Token> tokens = lexer.tokenize();

        Assert.assertEquals(TokenType.NAMED, tokens.get(0).type());
        Assert.assertEquals(TokenType.STRING, tokens.get(1).type());
        Assert.assertEquals("Hello \"World\" \n \t", tokens.get(1).value());
    }

    @Test
    public void testSourceSpanLineAndColumn() {
        String dsl = "flow test {\n" +
                "    step op1\n" +
                "}";
        FlowLexer lexer = new FlowLexer(dsl, "test.flow");
        List<Token> tokens = lexer.tokenize();

        Token stepToken = tokens.get(3); // 'step'
        Assert.assertEquals(TokenType.STEP, stepToken.type());
        Assert.assertEquals(2, stepToken.span().startLine());
        Assert.assertEquals(5, stepToken.span().startColumn());
        Assert.assertEquals(16, stepToken.span().startOffset());
        Assert.assertEquals(20, stepToken.span().endOffset());
        Assert.assertEquals(2, stepToken.span().endLine());
        Assert.assertEquals(9, stepToken.span().endColumn());
        Assert.assertEquals("step", dsl.substring(stepToken.span().startOffset(), stepToken.span().endOffset()));
    }
}
