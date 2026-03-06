package com.team4u.framework.retry.recovery;

/**
 * 内置重试任务类型常量。
 */
public final class RetryTaskTypes {

    /**
     * 注解/代理模式下默认的快照恢复任务类型。
     * <p>
     * 该值为框架保留路由键，业务侧不应复用为其他自定义恢复处理器。
     */
    public static final String DEFAULT_PROXY_RECOVERY = "team4u.retry.proxy.default-recovery";

    private RetryTaskTypes() {
    }
}
