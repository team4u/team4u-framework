package com.team4u.criterion.parser.handler;

import com.team4u.criterion.model.ContainsAnyCriterion;
import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.model.value.Value;
import com.team4u.criterion.parser.CriterionParser;
import com.team4u.criterion.parser.SyntaxHandler;

import java.util.List;

/**
 * ContainsAny 语法处理器。
 * <p>
 * 处理集合与集合相交检查语法：
 * <ul>
 * <li>userTags containsAny ['VIP', 'KOL'] - 静态列表。</li>
 * <li>roles containsAny requiredRoles - 动态列表。</li>
 * </ul>
 */
public class ContainsAnySyntaxHandler implements SyntaxHandler {

    /**
     * 关键字
     */
    public static final String KEYWORD = "containsAny";

    @Override
    public Criterion tryParse(String subject,
                              CriterionParser.Context context) {
        // 检查 "containsAny" 操作符。
        if (!context.match(KEYWORD)) {
            return null;
        }

        List<Value<?>> values = context.consumeValueList();
        return context.wrapProperty(subject, new ContainsAnyCriterion(values));
    }
}