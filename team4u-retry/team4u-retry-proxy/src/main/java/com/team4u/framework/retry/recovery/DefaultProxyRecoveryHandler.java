package com.team4u.framework.retry.recovery;

/**
 * 默认代理任务恢复处理器 SPI 提供者
 */
public class DefaultProxyRecoveryHandler extends SnapshotRecoveryHandler {

    public DefaultProxyRecoveryHandler() {
        super(RetryTaskTypes.DEFAULT_PROXY_RECOVERY);
    }
}
