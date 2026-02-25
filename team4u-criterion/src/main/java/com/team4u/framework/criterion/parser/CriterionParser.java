package com.team4u.framework.criterion.parser;

import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.value.Value;
import com.team4u.framework.criterion.parser.token.Token;

import java.util.List;
import java.util.function.Function;

/**
 * 规则解析器
 */
public interface CriterionParser {

    /**
     * 默认主语关键字
     */
    String SUBJECT_IT = "it";

    /**
     * 将表达式解析为规则对象
     */
    Criterion parse(String expression);

    /**
     * 解析上下文接口
     * <p>
     * 为语法插件提供安全的 Token 流操作权限
     */
    interface Context {

        /**
         * 查看当前 Token 对象
         */
        Token peekToken();

        /**
         * 查看后续 Token 对象
         */
        Token peekToken(int forwardOffset);

        /**
         * 查看当前 Token（不消费）
         *
         * @return 当前 Token，如果已到末尾返回 null
         */
        String peek();

        /**
         * 查看后续 Token（不消费）
         *
         * @param forwardOffset 偏移量（0表示当前Token，1表示下一个，以此类推）
         * @return Token，如果越界返回 null
         */
        String peek(int forwardOffset);

        /**
         * 匹配并消费指定 Token（忽略大小写）
         *
         * @param expected 期望的 Token
         * @return 如果匹配成功返回 true 并消费该 Token，否则返回 false
         */
        boolean match(String expected);

        /**
         * 强制消费指定 Token，如果不匹配则抛出异常
         *
         * @param expected 期望的 Token
         */
        void consume(String expected);

        /**
         * 消费下一个 Token 作为主语（通常是标识符，但也支持数字或字符串）
         *
         * @return Token 值
         */
        String consumeSubject();

        /**
         * 消费下一个 Token 作为操作符
         *
         * @return Token 值
         */
        String consumeOperator();

        /**
         * 消费下一个 Token 作为值（原始形式，包含引号）
         *
         * @return Token 值
         */
        String consumeValue();

        /**
         * 解析值 Token（去除引号）
         *
         * @param token 原始 Token
         * @return 去除引号后的值
         */
        String parseValueToken(String token);

        /**
         * 递归解析表达式（用于支持括号嵌套或子表达式）
         *
         * @return 解析出的 Criterion
         */
        Criterion parseExpression();

        /**
         * 包裹属性访问
         * <p>
         * 如果 subject 为 "this"，直接返回 leaf；否则包裹为 PropertyCriterion
         *
         * @param subject 主语
         * @param leaf    叶子节点
         * @return 包裹后的 Criterion
         */
        Criterion wrapProperty(String subject, Criterion leaf);

        /**
         * 消费下一个 Token 并自动封装为 Value&lt;T&gt;
         * <p>
         * 如果 Token 以 $ 开头，视为变量，创建 VariableValue；
         * 否则视为静态值，使用 staticParser 转换后创建 FixedValue。
         *
         * @param type         目标类型（用于动态变量转换）
         * @param staticParser 静态值解析函数
         * @param <T>          值的类型
         * @return Value 对象
         */
        <T> Value<T> consumeAsValue(Class<T> type, Function<String, T> staticParser);

        /**
         * 消费下一个（或一组）Token 作为值列表
         * <p>
         * 支持单值 "admin" 和列表形式 "['admin', 'user']"
         *
         * @return Value 对象列表
         */
        List<Value<?>> consumeValueList();
    }
}