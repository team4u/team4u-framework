package com.team4u.framework.criterion;

import com.team4u.framework.base.instance.DynamicInstanceProvider;
import com.team4u.framework.criterion.compiler.CompilerRegistry;
import com.team4u.framework.criterion.compiler.CompilingVisitor;
import com.team4u.framework.criterion.compiler.CriterionCompiler;
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.VariableExtractor;
import com.team4u.framework.criterion.model.convert.ValueConverter;
import com.team4u.framework.criterion.model.convert.ValueConverterRegistry;
import com.team4u.framework.criterion.parser.CriterionParser;
import com.team4u.framework.criterion.parser.SyntaxHandler;
import com.team4u.framework.criterion.parser.impl.StandardCriterionParser;
import com.team4u.framework.criterion.trace.TraceNode;
import com.team4u.framework.criterion.trace.TraceRecorder;
import lombok.Getter;

import java.util.*;
import java.util.function.BiPredicate;

/**
 * 规则引擎门面类（不可变且线程安全）
 * <p>
 * 推荐通过 {@link Criteria#builder()} 构建自定义配置实例，
 * 或使用 {@link Criteria#global()} 获取默认实例。
 * </p>
 *
 * <pre>
 * // 场景1：普通业务（使用标准库）
 * Criteria criteria = Criteria.global();
 * boolean result = criteria.matches("age > 18 && status == 'ACTIVE'", userObj);
 *
 * // 场景2：特定业务线（需要定制）
 * Criteria financeCriteria = Criteria.builder()
 *         .addOperator("intersects", (actual, expected) -> {
 *             return !Collections.disjoint((Collection<?>) actual, (Collection<?>) expected);
 *         })
 *         .addValueConverter(new MoneyToFenConverter())
 *         .build();
 * financeCriteria.matches("tags intersects ['VIP', 'SVIP']", context);
 * </pre>
 *
 * @author jay.wu
 */
@Getter
public class Criteria {

    /**
     * 默认的全局标准实例（预配置且不可变）
     */
    private static final Criteria GLOBAL = new Criteria(
            StandardCriterionParser.global(),
            CompilerRegistry.global()
    );

    /**
     * 解析器
     */
    private final CriterionParser parser;

    /**
     * 编译器注册表
     */
    private final CompilerRegistry compilerRegistry;

    /**
     * 核心编译缓存提供者：表达式字符串 -> 编译后的函数
     */
    private final DynamicInstanceProvider<String, String, MatchPredicate> compiledProvider;

    /**
     * 建议通过 Builder 或 global() 获取实例
     *
     * @param parser           解析器（为 null 时使用标准解析器）
     * @param compilerRegistry 编译器注册表（为 null 时使用默认注册表）
     */
    public Criteria(CriterionParser parser, CompilerRegistry compilerRegistry) {
        this.parser = parser != null ? parser : new StandardCriterionParser();
        this.compilerRegistry = compilerRegistry != null ? compilerRegistry : new CompilerRegistry();
        this.compiledProvider = DynamicInstanceProvider.createStringLru(
                1000,
                input -> input,
                this::doCompileExpression
        );
    }

    /**
     * 获取带有标准语法的全局单例引擎
     * <p>
     * 适用于大多数场景，无需任何配置，直接复用全局单例。
     * </p>
     *
     * @return 预置标准语法的全局单例实例
     */
    public static Criteria global() {
        return GLOBAL;
    }

    /**
     * 创建一个全新的规则引擎建造者
     *
     * @return CriteriaBuilder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 解析表达式字符串为 Criterion 对象
     *
     * @param expression 规则表达式，如 "age > 18 && name == 'test'"
     * @return 解析后的 Criterion 对象
     * @throws IllegalStateException 当解析器未设置时抛出
     */
    public Criterion parse(String expression) {
        if (parser == null) {
            throw new IllegalStateException("Parser not set");
        }
        return parser.parse(expression);
    }

    /**
     * 执行表达式匹配，使用对象作为实际值
     *
     * @param expression 规则表达式
     * @param actual     实际值对象
     * @return 是否匹配成功
     */
    public boolean matches(String expression, Object actual) {
        return matches(expression, MatchContext.of(actual));
    }

    /**
     * 执行表达式匹配，使用匹配上下文
     *
     * @param expression 规则表达式
     * @param context    匹配上下文
     * @return 是否匹配成功
     */
    public boolean matches(String expression, MatchContext context) {
        if (expression == null) {
            return false;
        }

        // 从缓存获取（或触发编译）
        MatchPredicate function = compileExpression(expression);
        // 执行闭包
        return function.test(context);
    }

    /**
     * 获取编译后的匹配谓词（带 LRU 缓存）
     *
     * @param expression 规则表达式
     * @return 编译后的匹配谓词
     */
    public MatchPredicate compileExpression(String expression) {
        return compiledProvider.get(expression);
    }

    /**
     * 执行"解析 + 编译"全流程
     */
    private MatchPredicate doCompileExpression(String expression) {
        // 解析 (String -> Criterion AST)
        Criterion criterion = parse(expression);

        if (criterion == null) {
            return MatchPredicate.FALSE;
        }
        // 编译 (Criterion AST -> Function)
        CompilingVisitor visitor = new CompilingVisitor(compilerRegistry);
        return criterion.accept(visitor);
    }

    /**
     * 解析表达式中的变量名（包含属性名和动态变量名）
     *
     * @param expression 规则表达式
     * @return 变量名集合
     */
    public Set<String> getVariables(String expression) {
        return VariableExtractor.extract(parse(expression));
    }

    /**
     * 执行表达式匹配并返回追踪树
     *
     * @param expression 规则表达式
     * @param actual     实际值对象
     * @return 根追踪节点
     */
    public TraceNode trace(String expression, Object actual) {
        return trace(expression, MatchContext.of(actual));
    }

    /**
     * 执行表达式匹配并返回追踪树
     *
     * @param expression 规则表达式
     * @param context    匹配上下文
     * @return 根追踪节点
     */
    public TraceNode trace(String expression, MatchContext context) {
        if (expression == null) {
            return null;
        }

        // 1. 初始化追踪记录器
        TraceRecorder recorder = new TraceRecorder();
        context.setRecorder(recorder);

        // 2. 获取编译后的函数 (带 Tracing 装饰器的)
        MatchPredicate function = compileExpression(expression);

        // 3. 执行
        function.test(context);

        // 4. 返回根节点
        return recorder.getRoot();
    }

    /**
     * 规则引擎构造器
     * <p>
     * 用于组装自定义的语法、操作符、转换器和编译器。
     * 组装完成后生成的 {@link Criteria} 是不可变且线程安全的。
     */
    public static class Builder {

        /**
         * 编译器注册表 (当前实例私有副本)
         */
        @Getter
        private final CompilerRegistry compilerRegistry = new CompilerRegistry();

        /**
         * 转换器注册表 (当前实例私有副本)
         */
        @Getter
        private final ValueConverterRegistry converterRegistry = new ValueConverterRegistry();

        /**
         * 自定义语法处理器列表
         */
        private final List<SyntaxHandler> customHandlers = new ArrayList<>();

        /**
         * 自定义动态操作符映射
         */
        private final Map<String, BiPredicate<Object, Object>> customOperators = new HashMap<>();

        /**
         * 包级私有构造，强制用户通过 {@link Criteria#builder()} 获取实例
         */
        Builder() {
            // 默认行为：从全局原型中拷贝标准策略 (O(1) 内存操作，极速且隔离)
            this.compilerRegistry.addAll(CompilerRegistry.global());
            this.converterRegistry.addAll(ValueConverterRegistry.global());
        }

        /**
         * 清空所有预置策略
         * <p>
         * 适用于需要完全“裸”规则引擎的场景（沙箱环境），以避免潜在的安全风险或干扰。
         * </p>
         *
         * @return 当前 Builder 实例
         */
        public Builder clear() {
            this.compilerRegistry.unregisterAll();
            this.converterRegistry.unregisterAll();
            return this;
        }

        /**
         * 添加自定义的高级语法处理器
         *
         * @param handler 语法处理器
         * @return 当前 Builder 实例
         */
        public Builder addSyntaxHandler(SyntaxHandler handler) {
            this.customHandlers.add(handler);
            return this;
        }

        /**
         * 添加轻量级的自定义动态操作符
         *
         * @param operator 操作符名称
         * @param logic    比较逻辑
         * @return 当前 Builder 实例
         */
        public Builder addOperator(String operator, BiPredicate<Object, Object> logic) {
            this.customOperators.put(operator, logic);
            return this;
        }

        /**
         * 添加自定义的值转换器
         *
         * @param converter 值转换器
         * @return 当前 Builder 实例
         */
        public Builder addValueConverter(ValueConverter converter) {
            this.converterRegistry.register(converter);
            return this;
        }

        /**
         * 添加针对特定 Criterion 节点的自定义编译器
         *
         * @param compiler 编译器实例
         * @return 当前 Builder 实例
         */
        public Builder addCompiler(CriterionCompiler<?> compiler) {
            this.compilerRegistry.register(compiler);
            return this;
        }

        /**
         * 构建不可变的规则引擎实例
         *
         * @return 不可变的规则引擎实例
         */
        public Criteria build() {
            // 初始化解析器 (注入已包含自定义转换器的注册表)
            StandardCriterionParser parser = new StandardCriterionParser(converterRegistry);
            // 注入自定义动态操作符
            customOperators.forEach(parser::addOperator);
            // 注入自定义语法处理器
            customHandlers.forEach(parser::addHandler);

            // 返回冻结状态的执行引擎
            return new Criteria(parser, compilerRegistry);
        }
    }
}