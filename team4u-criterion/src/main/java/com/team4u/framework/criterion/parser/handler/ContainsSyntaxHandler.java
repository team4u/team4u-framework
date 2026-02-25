package com.team4u.framework.criterion.parser.handler;

import com.team4u.framework.criterion.model.ContainsCriterion;
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.value.Value;
import com.team4u.framework.criterion.model.value.ValueFactory;
import com.team4u.framework.criterion.parser.CriterionParser;
import com.team4u.framework.criterion.parser.SyntaxHandler;

/**
 * Contains 语法处理器
 * <p>
 * 处理 "contains" 集合包含检查语法：
 * <ul>
 * <li>roles contains 'admin'</li>
 * <li>ids contains 1</li>
 * <li>roles contains requiredRole - 动态变量</li>
 * </ul>
 */
public class ContainsSyntaxHandler implements SyntaxHandler {

    /**
     * 关键字
     */
    public static final String KEYWORD = "contains";

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        // 检查 "contains" 操作符
        if (!context.match(KEYWORD)) {
            return null;
        }

        String token = context.consumeValue();

        // 使用 ValueFactory 自动类型推断机制
        Value<Object> valueProvider = ValueFactory.createAuto(token);

        return context.wrapProperty(subject, new ContainsCriterion(valueProvider));
    }
}