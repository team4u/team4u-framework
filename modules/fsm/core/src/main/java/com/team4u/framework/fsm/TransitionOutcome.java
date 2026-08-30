package com.team4u.framework.fsm;

/**
 * 一次状态迁移的判定结果。
 *
 * @author jay.wu
 */
public enum TransitionOutcome {

    /** 已命中迁移并成功执行全部动作。 */
    TRANSITIONED,

    /** 当前状态和事件组合下没有任何候选迁移。 */
    NO_TRANSITION,

    /** 存在候选迁移，但所有守卫均拒绝执行。 */
    GUARD_REJECTED;

    /**
     * 判断迁移是否成功。
     *
     * @return 成功迁移时返回 {@code true}
     */
    public boolean isAccepted() {
        return this == TRANSITIONED;
    }
}
