package com.team4u.framework.criterion.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通配符匹配规则
 */
@Getter
@AllArgsConstructor
public class WildcardCriterion extends Criterion {
    private final String pattern;
}
