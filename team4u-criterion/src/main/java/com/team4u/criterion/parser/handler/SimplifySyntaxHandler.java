package com.team4u.criterion.parser.handler;

import cn.hutool.core.util.NumberUtil;
import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.model.SmartCompareCriterion;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.ValueFactory;
import com.team4u.criterion.parser.CriterionParser;
import com.team4u.criterion.parser.SyntaxHandler;

import java.math.BigDecimal;

import static com.team4u.criterion.parser.CriterionParser.SUBJECT_IT;

/**
 * 极简版语法糖处理器
 * <p>
 * 处理逻辑：
 * 1. 如果没有下一个 Token (或遇到逻辑连接符) -> 隐式相等 (18 -> it == 18)
 * 2. 其他情况 -> 不是简写，跳过
 */
public class SimplifySyntaxHandler implements SyntaxHandler {

    @Override
    public Criterion tryParse(String tokenAsValue, CriterionParser.Context context) {
        // 由于在 StandardCriterionParser 中 subject 是通过 consumeValue() 取出来的了，
        // tokenAsValue 就代表了极简语法糖的值本身 (例如 18 或者 'admin')
        String nextToken = context.peek();

        // 场景: 简单值 (18 或 'admin')
        // 如果后面没有操作符，说明这个 token 本身就是值，主语隐含为 'it'
        if (isEndOfStatement(nextToken)) {
            return context.wrapProperty(SUBJECT_IT, createEqualsCriterion(tokenAsValue));
        }

        // 既然这是一个简写值，如果后面跟着关系操作符，说明它其实不是值而是主语(如 age > 18)
        // 交给下一级的处理
        return null;
    }

    /**
     * 判断是否到达当前单项表达式的末尾
     */
    private boolean isEndOfStatement(String token) {
        // null 表示字符串结束
        if (token == null)
            return true;
        // 遇到逻辑连接符或右括号，也表示当前单项结束
        return "&&".equals(token) || "||".equals(token) || ")".equals(token);
    }

    private Criterion createEqualsCriterion(String token) {
        // 数值处理
        if (NumberUtil.isNumber(token)) {
            return new SmartCompareCriterion("==", new FixedValue<>(new BigDecimal(token)));
        }
        // 字符串处理：使用 ValueFactory 统一去引号和反转义
        String value = ValueFactory.parseString(token);
        return new SmartCompareCriterion("==", new FixedValue<>(value));
    }

}