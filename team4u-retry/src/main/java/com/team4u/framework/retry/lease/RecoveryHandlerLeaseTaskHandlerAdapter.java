package com.team4u.framework.retry.lease;

import com.team4u.framework.lease.LeaseTaskHandler;
import com.team4u.framework.lease.LeaseWorker;
import com.team4u.framework.retry.recovery.RecoveryHandler;

/**
 * 将 {@link RecoveryHandler} 适配为 {@link LeaseTaskHandler} 的包装类。
 * <p>
 * 使得原有的重试恢复逻辑可以无缝接入 {@link LeaseWorker} 的任务执行流程中。
 */
public class RecoveryHandlerLeaseTaskHandlerAdapter implements LeaseTaskHandler {

    private final RecoveryHandler delegate;

    /**
     * 构造适配器
     *
     * @param delegate 被适配的原始恢复处理器
     */
    public RecoveryHandlerLeaseTaskHandlerAdapter(RecoveryHandler delegate) {
        this.delegate = delegate;
    }

    /**
     * 获取原始的恢复处理器
     *
     * @return 原始处理器
     */
    public RecoveryHandler getDelegate() {
        return delegate;
    }

    @Override
    public String key() {
        return delegate.key();
    }

    @Override
    public void handle(String payload) throws Exception {
        delegate.recover(payload);
    }
}
