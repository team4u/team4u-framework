package com.team4u.framework.lease.enums;

/**
 * 租约任务失败原因枚举
 * <p>
 * 用于标识任务进入失败（FAILED）状态的具体诱因，便于后续审计、统计或配置相应的重试策略。
 */
public enum LeaseTaskFailureReason {
    /**
     * 处理器异常：执行过程中业务代码抛出了未捕获的异常
     */
    HANDLER_EXCEPTION,
    /**
     * 重试耗尽：经过多次尝试后仍未成功，达到策略配置的上限
     */
    RETRY_EXHAUSTED,
    /**
     * 策略熔断：被内部管理策略主动终止执行（如检测到下游过载或环境不健康）
     */
    ABORTED_BY_POLICY,
    /**
     * 缺失处理器：本地 Worker 未注册该任务类型对应的处理器
     */
    MISSING_HANDLER,
    /**
     * 人工干预：由管理员通过管理后台或 API 显式标记为失败
     */
    MANUAL_FAIL,
    /**
     * 契约违反：生命周期感知型处理器（LeaseLifecycleAwareTaskHandler）执行完毕但未显式调用 close/release
     */
    HANDLER_CONTRACT_VIOLATION
}
