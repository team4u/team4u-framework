package com.team4u.criterion.parser.handler;

import com.team4u.criterion.model.ContainsAllCriterion;
import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.model.value.Value;
import com.team4u.criterion.parser.CriterionParser;
import com.team4u.criterion.parser.SyntaxHandler;

import java.util.List;

/**
 * ContainsAll 语法处理器。
 * <p>
 * 处理集合全集包含检查语法：
 * <ul>
 * <li>userTags containsAll ['VIP', 'KOL'] - 静态列表。</li>
 * <li>roles containsAll requiredRoles - 动态列表。</li>
 * </ul>
 */
public class ContainsAllSyntaxHandler implements SyntaxHandler {

    /**
     * 关键字
     */
    public static final String KEYWORD = "containsAll";

    @Override
    public Criterion tryParse(String subject,
                              CriterionParser.Context context) {
        // 检查 "containsAll" 操作符。
        if (!context.match(KEYWORD)) {
            return null;
        }

        List<Value<?>> values = context.consumeValueList();
        return context.wrapProperty(subject, new ContainsAllCriterion(values));
    }
}
