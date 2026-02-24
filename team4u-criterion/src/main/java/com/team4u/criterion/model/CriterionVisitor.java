package com.team4u.criterion.model;

/**
 * 规则访问者
 */
public interface CriterionVisitor<R> {

    /**
     * 访问规则
     */
    R visit(Criterion criterion);
}