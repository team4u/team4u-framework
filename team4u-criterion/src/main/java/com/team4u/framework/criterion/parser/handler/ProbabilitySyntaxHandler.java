package com.team4u.framework.criterion.parser.handler;

import com.team4u.framework.criterion.model.ProbabilityCriterion;
import com.team4u.framework.criterion.model.value.Value;

/**
 * 概率语法处理器
 * <p>
 * 处理 "prob" 关键字的概率匹配语法：
 * <ul>
 * <li>it prob 0.3 - 30% 概率命中</li>
 * <li>it prob 0.5 - 50% 概率命中</li>
 * <li>it prob $hitRate - 动态变量</li>
 * </ul>
 *
 * @author jay.wu
 */
public class ProbabilitySyntaxHandler extends SimpleKeywordHandler {

    /**
     * 关键字
     */
    public static final String KEYWORD = "prob";

    @SuppressWarnings("unchecked")
    public ProbabilitySyntaxHandler() {
        super(KEYWORD, Number.class,
                (subject, value) -> new ProbabilityCriterion((Value<Number>) value));
    }
}
