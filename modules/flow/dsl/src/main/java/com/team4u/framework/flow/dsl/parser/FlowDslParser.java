package com.team4u.framework.flow.dsl.parser;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.dsl.lexer.FlowLexer;
import com.team4u.framework.flow.dsl.lexer.Token;
import com.team4u.framework.flow.dsl.lexer.TokenType;

import java.time.Duration;
import java.util.*;

/**
 * 流程文本 DSL 语法解析器（Flow DSL Parser）。
 *
 * <p>基于递归下降算法将 Token 流解析为纯数据模型的 {@link FlowDefinition} AST，并保留精确的 {@link SourceSpan}。</p>
 *
 * @author jay.wu
 */
public final class FlowDslParser {

    private final List<Token> tokens;
    private final String sourceName;
    private int current = 0;

    public FlowDslParser(List<Token> tokens, String sourceName) {
        this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
        this.sourceName = sourceName;
    }

    public static FlowDefinition parse(String dsl, String sourceName) {
        FlowLexer lexer = new FlowLexer(dsl, sourceName);
        List<Token> tokens = lexer.tokenize();
        return new FlowDslParser(tokens, sourceName).parseDefinition();
    }

    public static FlowDefinition parse(String dsl) {
        return parse(dsl, null);
    }

    /**
     * 解析顶层 FlowDefinition。
     *
     * @return 流程定义 AST
     */
    public FlowDefinition parseDefinition() {
        Token startToken = peek();
        int schema = 1;

        // 可选头部 schema 1
        if (match(TokenType.SCHEMA)) {
            Token schemaToken = consume(TokenType.NUMBER, "Expected schema version number after 'schema'");
            schema = ((Number) schemaToken.value()).intValue();
            if (schema != 1) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.DSL_UNSUPPORTED_SCHEMA,
                        "Unsupported DSL schema version: " + schema + " (currently only schema 1 is supported)",
                        schemaToken.span()));
            }
        }

        consume(TokenType.FLOW, "Expected 'flow' declaration");
        Token idToken = consumeIdentifierOrString("Expected flow ID identifier");
        String flowId = idToken.text();

        String version = "1";
        if (match(TokenType.VERSION)) {
            Token verToken = advance();
            if (verToken.type() != TokenType.IDENTIFIER && verToken.type() != TokenType.NUMBER && verToken.type() != TokenType.STRING) {
                throw error(verToken, "Expected version identifier, number or string after 'version'");
            }
            version = verToken.text();
        }

        consume(TokenType.LBRACE, "Expected '{' to start flow body");
        List<FlowSpec> statements = parseStatements();
        Token endToken = consume(TokenType.RBRACE, "Expected '}' to close flow body");

        FlowSpec root;
        if (statements.isEmpty()) {
            root = new SequenceSpec(Collections.<FlowSpec>emptyList(), span(startToken, endToken));
        } else if (statements.size() == 1) {
            root = statements.get(0);
        } else {
            root = new SequenceSpec(statements, span(startToken, endToken));
        }

        FlowDefinition def = new FlowDefinition(schema, flowId, version, root, sourceName, span(startToken, endToken));
        consume(TokenType.EOF, "Unexpected content after flow definition");
        return def;
    }

    private List<FlowSpec> parseStatements() {
        List<FlowSpec> statements = new ArrayList<FlowSpec>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            FlowSpec stmt = parseStatement();
            if (stmt != null) {
                statements.add(stmt);
            }
        }
        return statements;
    }

    private FlowSpec parseStatement() {
        Token token = peek();

        if (match(TokenType.STEP)) {
            return parseStep(token);
        } else if (match(TokenType.ROUTE)) {
            return parseRoute(token);
        } else if (match(TokenType.FIRST_APPLICABLE)) {
            return parseFirstApplicable(token);
        } else if (match(TokenType.RECOVER)) {
            return parseRecover(token);
        } else if (match(TokenType.PARALLEL)) {
            return parseParallel(token);
        } else if (match(TokenType.AWAIT)) {
            return parseAwait(token);
        } else if (match(TokenType.SCOPE)) {
            return parseScope(token);
        } else if (match(TokenType.TIMEOUT)) {
            return parseTimeoutScope(token);
        } else if (match(TokenType.POLICY)) {
            return parsePolicyScope(token);
        } else if (match(TokenType.RETRY)) {
            return parseRetryScope(token);
        } else if (match(TokenType.NAMED)) {
            return parseNamedScope(token);
        } else if (match(TokenType.ACCEPTED)) {
            return parseComplete(token, CompleteSpec.CompleteKind.ACCEPTED);
        } else if (match(TokenType.REJECTED)) {
            return parseComplete(token, CompleteSpec.CompleteKind.REJECTED);
        } else if (match(TokenType.SKIPPED)) {
            return parseComplete(token, CompleteSpec.CompleteKind.SKIPPED);
        } else if (match(TokenType.FAILED)) {
            return parseComplete(token, CompleteSpec.CompleteKind.FAILED);
        }

        throw error(token, "Unexpected token in statement: " + token.text());
    }

    private FlowSpec parseStep(Token startToken) {
        Token opToken = consumeIdentifier("Expected operation ID after 'step'");
        SymbolRef operation = SymbolRef.of(opToken.text(), opToken.span());

        SymbolRef project = null;
        SymbolRef merge = null;
        List<ModifierSpec> modifiers = new ArrayList<ModifierSpec>();
        Token endToken = opToken;

        if (match(TokenType.LBRACE)) {
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                Token modStart = peek();
                if (match(TokenType.PROJECT)) {
                    if (project != null) {
                        throw new FlowDiagnosticException(new Diagnostic(
                                DiagnosticCodes.DUPLICATE_STEP_PROJECT,
                                "Duplicate 'project' declaration in step",
                                modStart.span()));
                    }
                    Token projToken = consumeIdentifier("Expected projector ID after 'project'");
                    project = SymbolRef.of(projToken.text(), projToken.span());
                } else if (match(TokenType.MERGE)) {
                    if (merge != null) {
                        throw new FlowDiagnosticException(new Diagnostic(
                                DiagnosticCodes.DUPLICATE_STEP_MERGE,
                                "Duplicate 'merge' declaration in step",
                                modStart.span()));
                    }
                    Token mergeToken = consumeIdentifier("Expected merger ID after 'merge'");
                    merge = SymbolRef.of(mergeToken.text(), mergeToken.span());
                } else if (match(TokenType.OPTIONAL)) {
                    modifiers.add(new OptionalModifierSpec(modStart.span()));
                } else if (match(TokenType.NAMED)) {
                    Token nameToken = consume(TokenType.STRING, "Expected string label after 'named'");
                    modifiers.add(new NamedModifierSpec(nameToken.text(), span(modStart, nameToken)));
                } else if (match(TokenType.TIMEOUT)) {
                    Token durToken = consume(TokenType.DURATION, "Expected duration literal after 'timeout'");
                    modifiers.add(new TimeoutModifierSpec((Duration) durToken.value(), span(modStart, durToken)));
                } else if (match(TokenType.POLICY)) {
                    Token policyToken = consumeIdentifier("Expected policy ID after 'policy'");
                    SymbolRef policyRef = SymbolRef.of(policyToken.text(), policyToken.span());
                    SymbolRef keyRef = parseOptionalKey();
                    Map<String, Object> config = parseOptionalConfigBlock("Expected '}' after policy configuration");
                    if (keyRef == null && config.containsKey("key")) {
                        Object keyVal = config.get("key");
                        keyRef = SymbolRef.of(String.valueOf(keyVal));
                    }
                    modifiers.add(new PolicyModifierSpec(policyRef, keyRef, config, span(modStart, previous())));
                } else if (match(TokenType.RETRY)) {
                    Token retryToken = consumeIdentifier("Expected retry policy ID after 'retry'");
                    SymbolRef retryRef = SymbolRef.of(retryToken.text(), retryToken.span());
                    Map<String, Object> config = parseOptionalConfigBlock("Expected '}' after retry configuration");
                    modifiers.add(new RetryModifierSpec(retryRef, config, span(modStart, previous())));
                } else {
                    throw error(modStart, "Unexpected step modifier: " + modStart.text());
                }
            }
            endToken = consume(TokenType.RBRACE, "Expected '}' to close step modifier block");
        }

        return new StepSpec(operation, project, merge, modifiers, span(startToken, endToken));
    }

    private FlowSpec parseRoute(Token startToken) {
        Token selectorToken = consumeIdentifier("Expected route selector operation ID after 'route'");
        SymbolRef selector = SymbolRef.of(selectorToken.text(), selectorToken.span());
        consume(TokenType.LBRACE, "Expected '{' to start route block");

        List<CaseSpec> cases = new ArrayList<CaseSpec>();
        FlowSpec otherwise = null;

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token caseStart = peek();
            if (match(TokenType.CASE)) {
                Token keyToken = advance();
                if (keyToken.type() != TokenType.IDENTIFIER && keyToken.type() != TokenType.STRING
                        && keyToken.type() != TokenType.NUMBER) {
                    throw error(keyToken, "Expected case key literal (identifier, string, or number)");
                }
                FlowSpec branch = parseBracedBody(caseStart, "Expected '{' to start case branch", "Expected '}' to close case branch");
                cases.add(new CaseSpec(keyToken.text(), branch, span(caseStart, previous())));
            } else if (match(TokenType.OTHERWISE)) {
                if (otherwise != null) {
                    throw new FlowDiagnosticException(new Diagnostic(
                            DiagnosticCodes.DUPLICATE_OTHERWISE,
                            "Duplicate 'otherwise' branch in route block",
                            caseStart.span()));
                }
                otherwise = parseBracedBody(caseStart, "Expected '{' to start otherwise branch", "Expected '}' to close otherwise branch");
            } else {
                throw error(caseStart, "Expected 'case' or 'otherwise' in route block");
            }
        }

        Token endToken = consume(TokenType.RBRACE, "Expected '}' to close route block");
        return new RouteSpec(selector, cases, otherwise, span(startToken, endToken));
    }

    private FlowSpec parseFirstApplicable(Token startToken) {
        consume(TokenType.LBRACE, "Expected '{' after 'firstApplicable'");
        List<FlowSpec> branches = new ArrayList<FlowSpec>();

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.LBRACE)) {
                Token branchStart = previous();
                List<FlowSpec> branchStmts = parseStatements();
                Token branchEnd = consume(TokenType.RBRACE, "Expected '}' to close branch");
                branches.add(toSequenceOrSingle(branchStmts, span(branchStart, branchEnd)));
            } else {
                branches.add(parseStatement());
            }
        }

        Token endToken = consume(TokenType.RBRACE, "Expected '}' after firstApplicable block");
        return new FirstApplicableSpec(branches, span(startToken, endToken));
    }

    private FlowSpec parseRecover(Token startToken) {
        consume(TokenType.LBRACE, "Expected '{' after 'recover'");
        consume(TokenType.BODY, "Expected 'body' block in recover");
        FlowSpec body = parseBracedBody(startToken, "Expected '{' after 'body'", "Expected '}' to close body block");

        consume(TokenType.ON_FAILURE, "Expected 'onFailure' block in recover");
        FlowSpec onFailure = parseBracedBody(previous(), "Expected '{' after 'onFailure'", "Expected '}' to close onFailure block");

        Token endToken = consume(TokenType.RBRACE, "Expected '}' after recover block");
        return new RecoverSpec(body, onFailure, span(startToken, endToken));
    }

    private FlowSpec parseParallel(Token startToken) {
        consume(TokenType.LBRACE, "Expected '{' after 'parallel'");
        List<BranchSpec> branches = new ArrayList<BranchSpec>();
        SymbolRef join = null;

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token token = peek();
            if (match(TokenType.BRANCH)) {
                Token nameToken = consumeIdentifier("Expected branch name after 'branch'");
                FlowSpec flow = parseBracedBody(token, "Expected '{' after branch name", "Expected '}' to close branch");
                branches.add(new BranchSpec(nameToken.text(), flow, span(token, previous())));
            } else if (match(TokenType.JOIN)) {
                if (join != null) {
                    throw new FlowDiagnosticException(new Diagnostic(
                            DiagnosticCodes.DUPLICATE_JOIN,
                            "Duplicate 'join' declaration in parallel block",
                            token.span()));
                }
                Token joinToken = consumeIdentifier("Expected join strategy ID after 'join'");
                join = SymbolRef.of(joinToken.text(), joinToken.span());
            } else {
                throw error(token, "Expected 'branch' or 'join' in parallel block");
            }
        }

        if (join == null) {
            throw error(startToken, "Parallel block must specify a 'join <join-id>' strategy");
        }

        Token endToken = consume(TokenType.RBRACE, "Expected '}' after parallel block");
        return new ParallelSpec(branches, join, span(startToken, endToken));
    }

    private FlowSpec parseAwait(Token startToken) {
        Token resumeToken = consumeIdentifier("Expected resume point ID after 'await'");
        return new AwaitSpec(SymbolRef.of(resumeToken.text(), resumeToken.span()), span(startToken, resumeToken));
    }

    private FlowSpec parseScope(Token startToken) {
        Token nameToken = consume(TokenType.STRING, "Expected string name after 'scope'");
        FlowSpec body = parseBracedBody(startToken, "Expected '{' after scope name", "Expected '}' to close scope block");
        return ControlSpec.scope(nameToken.text(), body, span(startToken, previous()));
    }

    private FlowSpec parseTimeoutScope(Token startToken) {
        Token durToken = consume(TokenType.DURATION, "Expected duration literal after 'timeout'");
        FlowSpec body = parseBracedBody(startToken, "Expected '{' after timeout duration", "Expected '}' to close timeout block");
        return ControlSpec.timeout((Duration) durToken.value(), body, span(startToken, previous()));
    }

    private FlowSpec parsePolicyScope(Token startToken) {
        Token policyToken = consumeIdentifier("Expected policy ID after 'policy'");
        SymbolRef policyRef = SymbolRef.of(policyToken.text(), policyToken.span());
        SymbolRef keyRef = parseOptionalKey();
        FlowSpec body = parseBracedBody(startToken, "Expected '{' after policy declaration", "Expected '}' to close policy block");
        return new ControlSpec(ControlSpec.ControlKind.POLICY, policyRef, keyRef, Collections.<String, Object>emptyMap(), body, span(startToken, previous()));
    }

    private FlowSpec parseRetryScope(Token startToken) {
        Token retryToken = consumeIdentifier("Expected retry policy ID after 'retry'");
        SymbolRef retryRef = SymbolRef.of(retryToken.text(), retryToken.span());
        FlowSpec body = parseBracedBody(startToken, "Expected '{' after retry declaration", "Expected '}' to close retry block");
        return new ControlSpec(ControlSpec.ControlKind.RETRY, retryRef, null, Collections.<String, Object>emptyMap(), body, span(startToken, previous()));
    }

    private FlowSpec parseNamedScope(Token startToken) {
        Token nameToken = consume(TokenType.STRING, "Expected string label after 'named'");
        FlowSpec body = parseBracedBody(startToken, "Expected '{' after named label", "Expected '}' to close named block");
        return ControlSpec.named(nameToken.text(), body, span(startToken, previous()));
    }

    private FlowSpec parseComplete(Token startToken, CompleteSpec.CompleteKind kind) {
        String literal = null;
        Token endToken = startToken;
        if (peek().type() == TokenType.STRING || peek().type() == TokenType.IDENTIFIER || peek().type() == TokenType.NUMBER) {
            Token valToken = advance();
            literal = valToken.text();
            endToken = valToken;
        }
        return new CompleteSpec(kind, literal, span(startToken, endToken));
    }

    private FlowSpec parseBracedBody(Token startToken, String openMsg, String closeMsg) {
        consume(TokenType.LBRACE, openMsg);
        List<FlowSpec> stmts = parseStatements();
        Token endToken = consume(TokenType.RBRACE, closeMsg);
        return toSequenceOrSingle(stmts, span(startToken, endToken));
    }

    private SymbolRef parseOptionalKey() {
        if (match(TokenType.KEY)) {
            Token keyToken = consumeIdentifier("Expected key ID after 'key'");
            return SymbolRef.of(keyToken.text(), keyToken.span());
        }
        return null;
    }

    private Map<String, Object> parseOptionalConfigBlock(String closeMsg) {
        if (match(TokenType.LBRACE)) {
            Map<String, Object> config = parseConfigBody();
            consume(TokenType.RBRACE, closeMsg);
            return config;
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> parseConfigBody() {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token keyToken = consumeIdentifierOrKeyword("Expected config property key");
            if (config.containsKey(keyToken.text())) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.DUPLICATE_CONFIG_KEY,
                        "Duplicate configuration key: " + keyToken.text(),
                        keyToken.span()));
            }
            match(TokenType.COLON);
            match(TokenType.EQUALS);
            Token valToken = advance();
            Object value = valToken.value() != null ? valToken.value() : valToken.text();
            config.put(keyToken.text(), value);
            match(TokenType.COMMA);
        }
        return config;
    }

    private FlowSpec toSequenceOrSingle(List<FlowSpec> stmts, SourceSpan span) {
        if (stmts.isEmpty()) {
            return new SequenceSpec(Collections.<FlowSpec>emptyList(), span);
        }
        if (stmts.size() == 1) {
            return stmts.get(0);
        }
        return new SequenceSpec(stmts, span);
    }

    private Token consumeIdentifierOrKeyword(String message) {
        Token token = peek();
        if (token.type() == TokenType.IDENTIFIER || token.type().keyword() != null) {
            return advance();
        }
        throw error(token, message + " (got '" + token.text() + "')");
    }

    private Token consumeIdentifier(String message) {
        Token token = peek();
        if (token.type() == TokenType.IDENTIFIER) {
            return advance();
        }
        throw error(token, message + " (got '" + token.text() + "')");
    }

    private Token consumeIdentifierOrString(String message) {
        Token token = peek();
        if (token.type() == TokenType.IDENTIFIER || token.type() == TokenType.STRING) {
            return advance();
        }
        throw error(token, message + " (got '" + token.text() + "')");
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) {
            return advance();
        }
        Token token = peek();
        throw error(token, message + " (expected " + type + ", got '" + token.text() + "')");
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private boolean check(TokenType type) {
        if (isAtEnd()) {
            return type == TokenType.EOF;
        }
        return peek().type() == type;
    }

    private Token advance() {
        if (!isAtEnd()) {
            current++;
        }
        return previous();
    }

    private boolean isAtEnd() {
        return peek().type() == TokenType.EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private SourceSpan span(Token start, Token end) {
        return new SourceSpan(
                sourceName,
                start.span().startLine(),
                start.span().startColumn(),
                end.span().endLine(),
                end.span().endColumn());
    }

    private FlowDiagnosticException error(Token token, String message) {
        return new FlowDiagnosticException(new Diagnostic(
                DiagnosticCodes.DSL_SYNTAX_ERROR,
                message,
                token.span()));
    }
}
