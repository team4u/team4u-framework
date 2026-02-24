package com.team4u.criterion.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 逻辑组合规则
 */
@Getter
@AllArgsConstructor
public class LogicCriterion extends Criterion {
    private final Operator operator;
    private final List<Criterion> children;

    public enum Operator {
        AND, OR
    }
}