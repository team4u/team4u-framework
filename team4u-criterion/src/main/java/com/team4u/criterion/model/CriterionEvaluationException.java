package com.team4u.criterion.model;

/**
 * 标准评估异常
 * <p>
 * 当启用严格模式时，评估过程中发生错误将抛出此异常
 *
 * @author jay.wu
 */
public class CriterionEvaluationException extends RuntimeException {

    public CriterionEvaluationException(String message) {
        super(message);
    }

    public CriterionEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }

    public CriterionEvaluationException(Throwable cause) {
        super(cause);
    }
}
