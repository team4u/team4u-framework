package com.team4u.framework.criterion.parser;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

/**
 * 规则解析异常
 */
public class CriterionParseException extends RuntimeException {
    /**
     * 异常相关的表达式
     */
    @Getter
    private final String expression;
    /**
     * 异常发生的Token索引
     */
    @Getter
    private final int tokenIndex;

    /**
     * 构造函数
     *
     * @param message    异常信息
     * @param expression 异常相关的表达式
     * @param tokenIndex 异常发生的Token索引
     */
    public CriterionParseException(String message, String expression, int tokenIndex) {
        super(StrUtil.format("{} (at token index: {}, expression: [{}])", message, tokenIndex, expression));
        this.expression = expression;
        this.tokenIndex = tokenIndex;
    }
}