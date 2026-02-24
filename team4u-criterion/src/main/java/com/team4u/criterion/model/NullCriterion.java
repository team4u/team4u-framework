package com.team4u.criterion.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 空值/存在性匹配规则
 */
@Getter
@AllArgsConstructor
public class NullCriterion extends Criterion {
    private final Type type;

    public enum Type {
        NULL,
        EMPTY,
        NOT_EMPTY
    }
}
