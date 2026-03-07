package com.team4u.framework.retry.recovery;

/**
 * 预定义的重试任务类型
 */
public final class RetryTaskTypes {

    /**
     * 代理模式默认的任务恢复类型标识
     * <p>
     * 该标识属于框架内部保留字段。在代理重试模式下，若未显式指定任务类型，
     * 系统将使用此默认值进行持久化和后续的任务恢复。
     */
    public static final String DEFAULT_PROXY_RECOVERY = "team4u.retry.proxy.default-recovery";

    private RetryTaskTypes() {
    }
}
