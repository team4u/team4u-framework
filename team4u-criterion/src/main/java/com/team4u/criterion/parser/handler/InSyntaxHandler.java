package com.team4u.criterion.parser.handler;

import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.model.InCriterion;
import com.team4u.criterion.model.value.Value;
import com.team4u.criterion.parser.CriterionParser;
import com.team4u.criterion.parser.SyntaxHandler;

import java.util.List;

/**
 * In 语法处理器
 * <p>
 * 处理 "in" 和 "not in" 集合包含语法：
 * <ul>
 * <li>role in ['admin', 'user']</li>
 * <li>id not in [1, 2, 3]</li>
 * <li>id in [1, 2, specialId] - 支持变量</li>
 * <li>it in group - 支持集合变量</li>
 * </ul>
 */
public class InSyntaxHandler implements SyntaxHandler {

    /**
     * 关键字
     */
    public static final String KEYWORD = "in";
    /**
     * 否定关键字
     */
    public static final String KEYWORD_NOT = "not";

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        boolean not;

        // 检查 "not in"
        if (KEYWORD_NOT.equalsIgnoreCase(context.peek()) &&
                KEYWORD.equalsIgnoreCase(context.peek(1))) {
            context.consume(KEYWORD_NOT);
            context.consume(KEYWORD);
            not = true;
        }
        // 检查 "in"
        else if (context.match(KEYWORD)) {
            not = false;
        } else {
            return null;
        }

        return parseInExpression(subject, context, not);
    }

    private Criterion parseInExpression(String subject, CriterionParser.Context context, boolean not) {
        List<Value<?>> values = context.consumeValueList();
        return context.wrapProperty(subject, new InCriterion(values, not));
    }
}