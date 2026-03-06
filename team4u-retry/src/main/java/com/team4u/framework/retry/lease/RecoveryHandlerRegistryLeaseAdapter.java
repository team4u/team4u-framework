package com.team4u.framework.retry.lease;

import com.team4u.framework.lease.LeaseTaskHandler;
import com.team4u.framework.lease.LeaseTaskHandlerRegistry;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;

import java.util.Optional;

/**
 * 将 {@link RecoveryHandlerRegistry} 适配为 {@link LeaseTaskHandlerRegistry} 的适配器类。
 * <p>
 * 由于重试框架现在的持久化后端集成了 team4u-lease，该适配器负责将重试业务中定义的 {@link RecoveryHandler}
 * 转换为租约系统可识别的 {@link LeaseTaskHandler}，从而实现统一的任务调度与执行。
 */
public class RecoveryHandlerRegistryLeaseAdapter implements LeaseTaskHandlerRegistry {

    private final RecoveryHandlerRegistry delegate;

    /**
     * 使用全局默认的恢复处理器注册表创建适配器
     */
    public RecoveryHandlerRegistryLeaseAdapter() {
        this(RecoveryHandlerRegistry.global());
    }

    /**
     * 使用指定的恢复处理器注册表创建适配器
     *
     * @param delegate 被委托的重试恢复处理器注册表
     */
    public RecoveryHandlerRegistryLeaseAdapter(RecoveryHandlerRegistry delegate) {
        this.delegate = delegate == null ? RecoveryHandlerRegistry.global() : delegate;
    }

    /**
     * 注册任务处理器
     * <p>
     * 支持自动拆箱（如果本身就是适配器）或通过内部装饰器将租约处理器转换为重试处理器。
     *
     * @param handler 租约任务处理器
     */
    @Override
    public void register(LeaseTaskHandler handler) {
        if (handler == null) {
            return;
        }
        // 如果已经是适配器，则提取原生的重试恢复处理器并注册
        if (handler instanceof RecoveryHandlerLeaseTaskHandlerAdapter) {
            delegate.register(((RecoveryHandlerLeaseTaskHandlerAdapter) handler).getDelegate());
            return;
        }
        // 否则通过装饰器模式进行转换注册
        delegate.register(new LeaseTaskHandlerRecoveryHandlerAdapter(handler));
    }

    /**
     * 根据任务类型获取处理器
     *
     * @param taskType 任务类型
     * @return 适配后的租约任务处理器
     */
    @Override
    public Optional<LeaseTaskHandler> get(String taskType) {
        Optional<RecoveryHandler> handler = delegate.get(taskType);
        if (!handler.isPresent()) {
            return Optional.empty();
        }
        // 将获取到的重试处理器包装成租约处理器返回
        return Optional.<LeaseTaskHandler>of(new RecoveryHandlerLeaseTaskHandlerAdapter(handler.get()));
    }

    /**
     * 将 LeaseTaskHandler 适配为 RecoveryHandler 的内部包装类
     */
    private static class LeaseTaskHandlerRecoveryHandlerAdapter implements RecoveryHandler {
        private final LeaseTaskHandler delegate;

        private LeaseTaskHandlerRecoveryHandlerAdapter(LeaseTaskHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public String key() {
            return delegate.key();
        }

        @Override
        public void recover(String payload) throws Exception {
            delegate.handle(payload);
        }
    }
}
