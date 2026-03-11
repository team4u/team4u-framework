package com.team4u.framework.lease.handler;

import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.lease.runtime.LeaseLifecycleExecutionContext;

/**
 * 租约生命周期感知型任务处理器
 * <p>
 * 实现此接口的任务处理器可以显式控制租约的生命周期（如主动闭环或带有特定延迟的释放）。
 * 适用于需要根据业务结果动态调整任务可见性，或需要手动管理任务状态回传的复杂场景。
 */
public interface LeaseLifecycleAwareTaskHandler extends LeaseTaskHandler {

    /**
     * 处理任务逻辑并显式接管生命周期管理
     * <p>
     * 处理器必须在执行结束前调用 {@code context.close(...)} 或 {@code context.release(...)} 来更新任务状态。
     * 如果方法返回时未调用上述方法，框架通常会将其标记为契约违反（HANDLER_CONTRACT_VIOLATION）。
     *
     * @param context 具备生命周期管理能力的执行上下文
     * @throws Exception 处理过程中的异常
     */
    void handleLifecycle(LeaseLifecycleExecutionContext context) throws Exception;

    @Override
    default void handle(LeaseExecutionContext context) throws Exception {
        throw new UnsupportedOperationException(
                "LeaseLifecycleAwareTaskHandler must be executed with LeaseLifecycleExecutionContext");
    }
}
