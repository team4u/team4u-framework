package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.handler.LeaseTaskHandlerRegistry;
import com.team4u.framework.lease.model.LeaseSubscription;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.retry.recovery.RecoveryContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 将 {@link RecoveryHandlerRegistry} 适配为 {@link LeaseTaskHandlerRegistry} 的适配器类。
 */
public class RecoveryHandlerRegistryLeaseAdapter implements LeaseTaskHandlerRegistry {

    private final RecoveryHandlerRegistry delegate;
    private final String queue;

    public RecoveryHandlerRegistryLeaseAdapter() {
        this(RecoveryHandlerRegistry.global(), RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE);
    }

    public RecoveryHandlerRegistryLeaseAdapter(RecoveryHandlerRegistry delegate) {
        this(delegate, RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE);
    }

    public RecoveryHandlerRegistryLeaseAdapter(RecoveryHandlerRegistry delegate, String queue) {
        this.delegate = delegate == null ? RecoveryHandlerRegistry.global() : delegate;
        this.queue = (queue == null || queue.trim().isEmpty()) ? RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE : queue;
    }

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

    @Override
    public Optional<LeaseTaskHandler> get(String queue, String taskType) {
        if (!this.queue.equals(queue)) {
            return Optional.empty();
        }
        Optional<RecoveryHandler<?>> handler = delegate.get(taskType);
        return handler.map(RecoveryHandlerLeaseTaskHandlerAdapter::new);
    }

    @Override
    public Set<LeaseSubscription> subscriptions() {
        Set<LeaseSubscription> subscriptions = new LinkedHashSet<LeaseSubscription>();
        subscriptions.add(LeaseSubscription.builder().queue(queue).build());
        return subscriptions;
    }

    private static class LeaseTaskHandlerRecoveryHandlerAdapter implements RecoveryHandler<Object> {
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
        public void recover(Object payload, RecoveryContext context) throws Exception {
            delegate.handle(LeaseExecutionContext.builder()
                    .taskId(context.getTaskId())
                    .taskType(taskType)
                    .payload(resolvePayload(payload))
                    .build());
        }

        private String resolvePayload(Object payload) {
            if (payload == null) {
                return null;
            }
            if (payload instanceof String) {
                return (String) payload;
            }
            // 该适配器不负责 payload 的 schema 转换。
            // 非 String 的恢复载荷需要由调用方先完成序列化，再适配为 LeaseTaskHandler。
            throw new IllegalArgumentException(
                    "LeaseTaskHandler adapter only supports String payload, actual type: "
                            + payload.getClass().getName());
        }
    }
}
