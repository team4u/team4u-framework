package com.team4u.framework.retry.recovery;

/**
 * 预定义重试任务类型
 */
public final class RetryTaskTypes {

    /**
     * 注解及代理模式默认的任务恢复类型
     * <p>
     * 框架内部保留字段，业务侧定义恢复处理器时应避免冲突。
     */
    public static final String DEFAULT_PROXY_RECOVERY = "team4u.retry.proxy.default-recovery";

    private RetryTaskTypes() {
    }
}
