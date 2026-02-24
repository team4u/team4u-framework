package com.team4u.criterion.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 匹配标准/规则的顶层接口
 * 仅用于承载数据，不包含任何业务逻辑
 */
@Setter
@Getter
public abstract class Criterion {

    /**
     * 原始表达式字符串 (例如 "> 18" 或 "in [1, 2]")
     */
    private String expression;

    /**
     * 接受访问者（用于遍历、序列化、打印等）
     */
    public <R> R accept(CriterionVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        // 优先返回解析时记录的原始表达式，实现自描述
        return expression != null ? expression : super.toString();
    }
}