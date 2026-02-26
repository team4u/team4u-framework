package com.team4u.framework.criterion.parser.impl;

import cn.hutool.core.util.StrUtil;
import com.team4u.framework.criterion.model.*;
import com.team4u.framework.criterion.model.convert.ValueConverterRegistry;
import com.team4u.framework.criterion.model.value.Value;
import com.team4u.framework.criterion.model.value.ValueFactory;
import com.team4u.framework.criterion.parser.CriterionKeywords;
import com.team4u.framework.criterion.parser.CriterionParseException;
import com.team4u.framework.criterion.parser.CriterionParser;
import com.team4u.framework.criterion.parser.SyntaxHandler;
import com.team4u.framework.criterion.parser.handler.*;
import com.team4u.framework.criterion.parser.token.Token;
import com.team4u.framework.criterion.parser.token.TokenType;
import com.team4u.framework.policy.core.OrderedPolicyChain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 标准表达式解析器
 * <p>
 * 基于插件责任链模式，支持通过注册 {@link SyntaxHandler} 扩展新语法。
 */
public class StandardCriterionParser implements CriterionParser {

    private static final StandardCriterionParser GLOBAL = new StandardCriterionParser(ValueConverterRegistry.global());

    /**
     * 获取全局共享的标准表达式解析器实例
     *
     * @return 全局解析器实例
     */
    public static StandardCriterionParser global() {
        return GLOBAL;
    }

    /**
     * 语法处理器注册表
     */
    private final SyntaxHandlerRegistrar registrar = new SyntaxHandlerRegistrar();
    // 持有动态处理器实例
    private final DynamicSyntaxHandler dynamicHandler = new DynamicSyntaxHandler();
    /**
     * 转换器注册表 (延迟初始化)
     */
    private ValueConverterRegistry converterRegistry;

    public StandardCriterionParser() {
        this(null);
    }

    public StandardCriterionParser(ValueConverterRegistry converterRegistry) {
        this.converterRegistry = converterRegistry;

        // 1. 注册标准内置插件
        registerStandardHandlers();

        // 2. 注册兜底处理器
        registrar.registerPolicy(new RelationalOperatorSyntaxHandler());
    }

    /**
     * 获取注册的语法处理器列表（按优先级排序）
     */
    public List<SyntaxHandler> getHandlers() {
        return registrar.policies();
    }

    public ValueConverterRegistry getConverterRegistry() {
        if (converterRegistry == null) {
            converterRegistry = new ValueConverterRegistry();
        }
        return converterRegistry;
    }

    private void registerStandardHandlers() {
        registrar.registerPolicy(new SimplifySyntaxHandler());
        registrar.registerPolicy(new ValueConverterSyntaxHandler(this::getConverterRegistry));
        registrar.registerPolicy(new IsSyntaxHandler());
        registrar.registerPolicy(new BetweenSyntaxHandler());
        registrar.registerPolicy(new InSyntaxHandler());
        registrar.registerPolicy(new ContainsSyntaxHandler());
        registrar.registerPolicy(new ContainsAnySyntaxHandler());
        registrar.registerPolicy(new ContainsAllSyntaxHandler());
        registrar.registerPolicy(new StringPatternSyntaxHandler.Regex(
                StringPatternSyntaxHandler.KEYWORD_REGEX,
                pattern -> new RegexCriterion(Pattern.compile(pattern))));
        registrar.registerPolicy(new StringPatternSyntaxHandler.Like(
                StringPatternSyntaxHandler.KEYWORD_LIKE, WildcardCriterion::new));
        registrar.registerPolicy(new ProbabilitySyntaxHandler());
        registrar.registerPolicy(new HashProbabilitySyntaxHandler());
        // 动态处理器作为标准处理器
        registrar.registerPolicy(dynamicHandler);
    }

    /**
     * 添加自定义操作符
     */
    public StandardCriterionParser addOperator(String operator, BiPredicate<Object, Object> logic) {
        dynamicHandler.addOperator(operator, logic);
        return this;
    }

    /**
     * 添加自定义语法处理器
     *
     * @param handler 语法处理器
     * @return 当前解析器实例
     */
    public StandardCriterionParser addHandler(SyntaxHandler handler) {
        registrar.registerPolicy(handler);
        return this;
    }

    @Override
    public Criterion parse(String expression) {
        if (StrUtil.isBlank(expression)) {
            return null;
        }
        List<Token> tokens = tokenize(expression);
        return new ParserContext(expression, tokens).parse();
    }

    private List<Token> tokenize(String expression) {
        return new CharTokenScanner(expression).scan();
    }

    /**
     * 内部语法处理器注册表
     */
    public static class SyntaxHandlerRegistrar extends OrderedPolicyChain<Context, SyntaxHandler> {

        public SyntaxHandlerRegistrar() {
            super(SyntaxHandler.class);
        }

        public List<SyntaxHandler> policies() {
            return getPolicies();
        }

        public void registerPolicy(SyntaxHandler handler) {
            register(handler);
        }
    }

    /**
     * 内部解析器
     */
    private class ParserContext implements Context {
        private final String expression;
        private final List<Token> tokens;
        private int pos = 0;

        // 记录每次 parsePrimary 尝试调用 handler 时的起始位置（subject 之后）
        private int predicateStartPos = 0;

        public ParserContext(String expression, List<Token> tokens) {
            this.expression = expression;
            this.tokens = tokens;
        }

        private CriterionParseException error(String message) {
            return new CriterionParseException(message, expression, pos);
        }

        public Criterion parse() {
            Criterion c = parseOr();
            if (pos < tokens.size()) {
                throw error("Unexpected token at end: " + tokens.get(pos).getValue());
            }
            return c;
        }

        private Criterion parseOr() {
            Criterion left = parseAnd();
            while (match(CriterionKeywords.OR)) {
                Criterion right = parseAnd();
                left = new LogicCriterion(LogicCriterion.Operator.OR, Arrays.asList(left, right));
            }
            return left;
        }

        private Criterion parseAnd() {
            Criterion left = parsePrimary();
            while (match(CriterionKeywords.AND)) {
                Criterion right = parsePrimary();
                left = new LogicCriterion(LogicCriterion.Operator.AND, Arrays.asList(left, right));
            }
            return left;
        }

        private Criterion parsePrimary() {
            if (match(CriterionKeywords.LEFT_PAREN)) {
                Criterion c = parseOr();
                consume(CriterionKeywords.RIGHT_PAREN);
                return c;
            }

            // 1. 解析主体 (Subject)
            String subject = consumeSubject();

            // 2. 记录谓词部分的起始 Token 索引
            this.predicateStartPos = pos;

            // 3. 遍历插件链
            for (SyntaxHandler handler : getHandlers()) {
                int snapshotPos = this.pos;

                Criterion criterion = handler.tryParse(subject, this);
                if (criterion != null) {
                    return criterion;
                }

                // 若解析失败，回滚位置与 Token 流游标
                this.pos = snapshotPos;
                this.predicateStartPos = snapshotPos;
            }

            throw error("Unable to parse expression for subject: " + subject);
        }

        @Override
        public Token peekToken() {
            return peekToken(0);
        }

        @Override
        public Token peekToken(int forwardOffset) {
            int targetPos = pos + forwardOffset;
            return targetPos >= tokens.size() ? null : tokens.get(targetPos);
        }

        @Override
        public String peek() {
            Token t = peekToken();
            return t == null ? null : t.getValue();
        }

        @Override
        public String peek(int forwardOffset) {
            Token t = peekToken(forwardOffset);
            return t == null ? null : t.getValue();
        }

        @Override
        public boolean match(String expected) {
            Token t = peekToken();
            if (t != null && t.getValue().equalsIgnoreCase(expected)) {
                pos++;
                return true;
            }
            return false;
        }

        @Override
        public void consume(String expected) {
            if (!match(expected)) {
                throw error("Expect '" + expected + "' but found "
                        + (peekToken() != null ? peekToken().getValue() : "EOF"));
            }
        }

        @Override
        public String consumeSubject() {
            Token t = peekToken();
            if (t == null) {
                throw error("Expected subject");
            }
            if (t.getType() != TokenType.IDENTIFIER && t.getType() != TokenType.NUMBER
                    && t.getType() != TokenType.STRING) {
                throw error("Expected subject but found " + t.getType());
            }
            pos++;
            return t.getValue();
        }

        @Override
        public String consumeOperator() {
            Token t = peekToken();
            if (t == null)
                throw error("Expected operator");
            if (t.getType() != TokenType.OPERATOR && t.getType() != TokenType.IDENTIFIER)
                throw error("Expected operator but found " + t.getType());
            pos++;
            return t.getValue();
        }

        @Override
        public String consumeValue() {
            if (pos >= tokens.size())
                throw error("Unexpected EOF");
            return tokens.get(pos++).getValue();
        }

        @Override
        public String parseValueToken(String token) {
            return ValueFactory.parseString(token);
        }

        @Override
        public Criterion parseExpression() {
            return parseOr();
        }

        @Override
        public Criterion wrapProperty(String subject, Criterion leaf) {
            // 1. 自动注入叶子节点的表达式字符串
            if (leaf != null && leaf.getExpression() == null) {
                String leafExpr = reconstructExpression(predicateStartPos, pos);
                if (leafExpr.isEmpty() && !SUBJECT_IT.equals(subject)) {
                    leafExpr = subject;
                }
                leaf.setExpression(leafExpr);
            }

            if (SUBJECT_IT.equalsIgnoreCase(subject)) {
                return leaf;
            }

            // 2. 创建 PropertyCriterion 并注入完整表达式
            PropertyCriterion pc = new PropertyCriterion(subject, leaf);
            String fullExpr = subject + " " + (leaf != null ? leaf.toString() : "");
            pc.setExpression(fullExpr);

            return pc;
        }

        /**
         * 重构表达式字符串
         */
        private String reconstructExpression(int start, int end) {
            if (start >= end) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (i > start)
                    sb.append(" ");
                sb.append(tokens.get(i).getValue());
            }
            return sb.toString();
        }

        @Override
        public <T> Value<T> consumeAsValue(Class<T> type, Function<String, T> staticParser) {
            String token = consumeValue();
            return ValueFactory.create(token, staticParser, type);
        }

        @Override
        public List<Value<?>> consumeValueList() {
            List<Value<?>> values = new ArrayList<>();

            if (match(CriterionKeywords.LEFT_BRACKET)) {
                if (!match(CriterionKeywords.RIGHT_BRACKET)) {
                    do {
                        values.add(ValueFactory.createAuto(consumeValue()));
                    } while (match(CriterionKeywords.COMMA));

                    consume(CriterionKeywords.RIGHT_BRACKET);
                }
            } else {
                // 单值场景
                values.add(ValueFactory.createAuto(consumeValue()));
            }

            return values;
        }
    }
}