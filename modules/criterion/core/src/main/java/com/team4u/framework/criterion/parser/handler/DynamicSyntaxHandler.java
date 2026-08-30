package com.team4u.framework.criterion.parser.handler;

import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.DynamicCriterion;
import com.team4u.framework.criterion.model.value.Value;
import com.team4u.framework.criterion.model.value.ValueFactory;
import com.team4u.framework.criterion.parser.CriterionParser;
import com.team4u.framework.criterion.parser.SyntaxHandler;
import com.team4u.framework.criterion.parser.token.Token;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * 动态语法处理器
 */
public class DynamicSyntaxHandler implements SyntaxHandler {

    // 使用忽略大小写的 Map 存储算子
    private final Map<String, BiPredicate<Object, Object>> operators = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public void addOperator(String operator, BiPredicate<Object, Object> logic) {
        operators.put(operator, logic);
    }

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        String operator = context.peek();

        // 检查是否为已注册的操作符
        if (operator == null || !operators.containsKey(operator)) {
            return null;
        }

        // 获取逻辑
        BiPredicate<Object, Object> logic = operators.get(operator);

        // 检查是否有下一个值 token
        Token valueToken = context.peekToken(1);
        if (valueToken == null) {
            return null; // 让后续处理器处理或在外层报错
        }

        context.consumeOperator();
        // 解析值
        String token = context.consumeValue();
        Value<Object> value = ValueFactory.createAuto(token);

        return context.wrapProperty(subject, new DynamicCriterion(operator, value, logic));
    }
}