package com.team4u.criterion.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.regex.Pattern;

/**
 * 正则匹配规则
 */
@Getter
@AllArgsConstructor
public class RegexCriterion extends Criterion {
    /**
     * 预编译的正则表达式 Pattern 对象
     */
    private final Pattern pattern;
}