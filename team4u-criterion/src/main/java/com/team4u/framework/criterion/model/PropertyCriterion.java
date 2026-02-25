package com.team4u.framework.criterion.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 属性提取规则
 */
@Getter
@AllArgsConstructor
public class PropertyCriterion extends Criterion {
    private final String name;
    private final Criterion criterion;
}