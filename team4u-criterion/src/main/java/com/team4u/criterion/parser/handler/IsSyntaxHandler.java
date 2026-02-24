package com.team4u.criterion.parser.handler;

import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.model.NullCriterion;
import com.team4u.criterion.parser.CriterionParser;
import com.team4u.criterion.parser.SyntaxHandler;

/**
 * Is 语法处理器
 * <p>
 * 处理 "is" 关键字的空值检查语法：
 * <ul>
 * <li>name is null</li>
 * <li>name is empty</li>
 * <li>name is not empty</li>
 * <li>name is not null (复合操作符形式)</li>
 * </ul>
 */
public class IsSyntaxHandler implements SyntaxHandler {

    /**
     * 关键字
     */
    public static final String KEYWORD = "is";
    /**
     * 否定关键字
     */
    public static final String KEYWORD_NOT = "not";
    /**
     * 空关键字
     */
    public static final String KEYWORD_NULL = "null";
    /**
     * 空（字符串或集合）关键字
     */
    public static final String KEYWORD_EMPTY = "empty";

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        // 匹配 "is"
        if (!context.match(KEYWORD)) {
            return null;
        }

        // 处理 "is not ..."
        if (context.match(KEYWORD_NOT)) {
            if (context.match(KEYWORD_EMPTY)) {
                return context.wrapProperty(subject, new NullCriterion(NullCriterion.Type.NOT_EMPTY));
            }
            // 支持 "is not null"
            if (context.match(KEYWORD_NULL)) {
                // 相当于 "is not null"，即 NOT_EMPTY 的一种变体，或者你可以扩展 NullCriterion 支持 NOT_NULL
                return context.wrapProperty(subject, new NullCriterion(NullCriterion.Type.NOT_EMPTY));
            }
            throw new RuntimeException("Expected '" + KEYWORD_EMPTY + "' or '" + KEYWORD_NULL
                    + "' after '" + KEYWORD + " " + KEYWORD_NOT + "'");
        }

        // 处理 "is null" / "is empty"
        if (context.match(KEYWORD_NULL)) {
            return context.wrapProperty(subject, new NullCriterion(NullCriterion.Type.NULL));
        }
        if (context.match(KEYWORD_EMPTY)) {
            return context.wrapProperty(subject, new NullCriterion(NullCriterion.Type.EMPTY));
        }

        return null;
    }
}
