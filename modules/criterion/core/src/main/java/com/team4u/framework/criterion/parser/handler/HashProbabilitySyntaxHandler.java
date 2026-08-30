package com.team4u.framework.criterion.parser.handler;

import com.team4u.framework.criterion.model.HashProbabilityCriterion;
import com.team4u.framework.criterion.model.value.Value;

/**
 * Hash 概率分流语法处理器
 * <p>
 * 处理 "hash" 关键字的概率分流语法：
 * <ul>
 * <li>userId hash 0.3 - 30% 的用户命中</li>
 * <li>region hash 0.5 - 50% 的地区命中</li>
 * <li>userId hash $experimentRate - 动态变量</li>
 * </ul>
 *
 * @author jay.wu
 */
public class HashProbabilitySyntaxHandler extends SimpleKeywordHandler {

    /**
     * 关键字
     */
    public static final String KEYWORD = "hash";

    @SuppressWarnings("unchecked")
    public HashProbabilitySyntaxHandler() {
        super(KEYWORD, Number.class,
                (subject, value) -> new HashProbabilityCriterion((Value<Number>) value));
    }
}
