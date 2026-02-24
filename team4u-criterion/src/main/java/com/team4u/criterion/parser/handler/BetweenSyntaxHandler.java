package com.team4u.criterion.parser.handler;

import com.team4u.criterion.model.BetweenCriterion;
import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.model.value.Value;
import com.team4u.criterion.parser.CriterionKeywords;
import com.team4u.criterion.parser.CriterionParser;
import com.team4u.criterion.parser.SyntaxHandler;
import com.team4u.criterion.util.FastNumberUtil;

import java.util.function.Function;

/**
 * Between 语法处理器
 * <p>
 * 处理闭区间和开区间的范围判断语法：
 * <ul>
 * <li>age between [18, 30] - 包含 18 和 30</li>
 * <li>score between (90, 100) - 不包含 90 和 100</li>
 * <li>age between [minAge, maxAge] - 动态变量</li>
 * </ul>
 */
public class BetweenSyntaxHandler implements SyntaxHandler {

    /**
     * 关键字
     */
    public static final String KEYWORD = "between";

    /**
     * 解析 Between 语法的核心逻辑
     *
     * @param context       解析上下文
     * @param valueType     边界值的目标类型
     * @param valueParser   字符串到边界值的解析函数
     * @param typeConverter 运行时值的 Comparable 转换器
     * @param <T>           边界值类型
     * @return Between 规则对象
     */
    public static <T> BetweenCriterion parseBody(CriterionParser.Context context,
                                                 Class<T> valueType,
                                                 Function<String, T> valueParser,
                                                 Function<Object, Comparable<?>> typeConverter) {
        // 解析开始括号
        boolean includeLower;
        if (context.match(CriterionKeywords.LEFT_BRACKET)) {
            includeLower = true;
        } else if (context.match(CriterionKeywords.LEFT_PAREN)) {
            includeLower = false;
        } else {
            throw new RuntimeException("Expected '" + CriterionKeywords.LEFT_BRACKET + "' or '"
                    + CriterionKeywords.LEFT_PAREN + "' after '" + KEYWORD + "'");
        }

        // 解析下界值
        Value<T> lower = context.consumeAsValue(valueType, valueParser);

        // 解析逗号
        context.consume(CriterionKeywords.COMMA);

        // 解析上界值
        Value<T> upper = context.consumeAsValue(valueType, valueParser);

        // 解析结束括号
        boolean includeUpper;
        if (context.match(CriterionKeywords.RIGHT_BRACKET)) {
            includeUpper = true;
        } else if (context.match(CriterionKeywords.RIGHT_PAREN)) {
            includeUpper = false;
        } else {
            throw new RuntimeException("Expected '" + CriterionKeywords.RIGHT_BRACKET + "' or '"
                    + CriterionKeywords.RIGHT_PAREN + "' after upper bound value");
        }

        return new BetweenCriterion(lower, upper, includeLower, includeUpper, typeConverter);
    }

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        if (!context.match(KEYWORD)) {
            return null;
        }

        return context.wrapProperty(
                subject,
                parseBody(context, Number.class, FastNumberUtil::toNumber,
                        s -> (Comparable<?>) FastNumberUtil.toNumber(s)));
    }
}