package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.handler.LeaseTaskHandlerRegistry;
import com.team4u.framework.lease.model.LeaseSubscription;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 注册表适配器。
 * <p>
 * 该类跨接了重试领域的 {@link RecoveryHandlerRegistry} 与租约系统的
 * {@link LeaseTaskHandlerRegistry}。
 * 它能够将传统的恢复处理器透明地转化为租约任务处理器，并统一管理租约队列（Queue）与任务类型（TaskType）的映射关系。
 * </p>
 */
public class RecoveryHandlerRegistryLeaseAdapter implements LeaseTaskHandlerRegistry {

    /**
     * 被适配的重试处理器注册表
     */
    private final RecoveryHandlerRegistry delegate;
    /**
     * 该运行时所监听的租约队列名称
     */
    private final String queue;

    public RecoveryHandlerRegistryLeaseAdapter(RecoveryHandlerRegistry delegate) {
        this(delegate, RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE);
    }

    public RecoveryHandlerRegistryLeaseAdapter(RecoveryHandlerRegistry delegate, String queue) {
        this.delegate = delegate == null ? RecoveryHandlerRegistry.global() : delegate;
        this.queue = (queue == null || queue.trim().isEmpty()) ? RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE : queue;
    }

    /**
     * 注册一个租约处理器。
     * <p>
     * 若传入的是 {@link RecoveryHandlerLeaseTaskHandlerAdapter}，则直接解包出内部的
     * RecoveryHandler 并注册。
     * 否则，将其包装为适配器后再行注册。
     * </p>
     */
    @Override
    public void register(String queue, String taskType, LeaseTaskHandler handler) {
        if (handler == null) {
            return;
        }
        if (!this.queue.equals(queue)) {
            throw new IllegalArgumentException(
                    "Recovery handler queue mismatch. expected=" + this.queue + ", actual=" + queue);
        }
        if (handler instanceof RecoveryHandlerLeaseTaskHandlerAdapter) {
            delegate.register(((RecoveryHandlerLeaseTaskHandlerAdapter) handler).getDelegate());
            return;
        }
        delegate.register(new LeaseTaskHandlerRecoveryHandlerAdapter(taskType, handler));
    }

    /**
     * 根据租约标识获取对应的适配处理器。
     */
    @Override
    public Optional<LeaseTaskHandler> get(String queue, String taskType) {
        if (!this.queue.equals(queue)) {
            return Optional.empty();
        }
        return delegate.get(taskType).map(handler -> {
            if (!(handler instanceof StringRecoveryHandler)) {
                throw new IllegalArgumentException(
                        "Lease recovery handler registry requires StringRecoveryHandler. taskType="
                                + taskType + ", handler=" + handler.getClass().getName());
            }
            return new RecoveryHandlerLeaseTaskHandlerAdapter((StringRecoveryHandler) handler);
        });
    }

    /**
     * 获取当前运行时需要订阅的租约队列集合。
     */
    @Override
    public Set<LeaseSubscription> subscriptions() {
        Set<LeaseSubscription> subscriptions = new LinkedHashSet<LeaseSubscription>();
        subscriptions.add(LeaseSubscription.builder().queue(queue).build());
        return subscriptions;
    }

    /**
     * 内部包装类：将普通的 LeaseTaskHandler 适配为 RecoveryHandler，用于反向集成。
     */
    private static class LeaseTaskHandlerRecoveryHandlerAdapter implements StringRecoveryHandler {
        private final String taskType;
        private final LeaseTaskHandler delegate;

        private LeaseTaskHandlerRecoveryHandlerAdapter(String taskType, LeaseTaskHandler delegate) {
            this.taskType = taskType;
            this.delegate = delegate;
        }

        @Override
        public String taskName() {
            return taskType;
        }

        @Override
        public void recover(String payload, RecoveryContext context) throws Exception {
            delegate.handle(LeaseExecutionContext.builder()
                    .taskId(context.getTaskId())
                    .taskType(taskType)
                    .payload(payload)
                    .build());
        }
    }
}
