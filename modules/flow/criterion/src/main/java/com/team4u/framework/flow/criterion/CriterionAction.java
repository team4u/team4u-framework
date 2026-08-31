package com.team4u.framework.flow.criterion;

/**
 * 表达式策略判定失败时的门控动作枚举。
 *
 * @author jay.wu
 */
public enum CriterionAction {
    /**
     * 产生 {@code Gate.reject(Reason)}，作为业务拒绝短路退出，不触发重试。
     */
    REJECT,

    /**
     * 产生 {@code Gate.fail(Failure)}，作为系统级失败异常退出，可被上层治理策略（如重试/容灾）捕获。
     */
    FAIL
}
