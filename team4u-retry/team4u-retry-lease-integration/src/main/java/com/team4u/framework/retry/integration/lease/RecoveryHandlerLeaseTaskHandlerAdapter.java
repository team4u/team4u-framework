package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.lease.runtime.LeaseWorker;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import lombok.Getter;

/**
 * 将 {@link RecoveryHandler} 适配为 {@link LeaseTaskHandler} 的包装类。
 * <p>
 * 使得原有的重试恢复逻辑可以无缝接入 {@link LeaseWorker} 的任务执行流程中。
 */
@Getter
public class RecoveryHandlerLeaseTaskHandlerAdapter implements LeaseTaskHandler {

    /**
     * 获取原始的恢复处理器
     */
    private final RecoveryHandler delegate;

    /**
     * 构造适配器
     *
     * @param delegate 被适配的原始恢复处理器
     */
    public RecoveryHandlerLeaseTaskHandlerAdapter(RecoveryHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void handle(LeaseExecutionContext context) throws Exception {
        delegate.recover(context.getPayload());
    }
}