package com.team4u.framework.lease.runtime;

import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseGrant;
import com.team4u.framework.lease.model.LeaseHandle;
import com.team4u.framework.lease.model.LeaseReleaseRequest;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 租约生命周期执行上下文
 * <p>
 * 提供 {@link #close(LeaseCloseRequest)} 和 {@link #release(LeaseReleaseRequest)} 方法，
 * 允许处理器直接向租约系统提交最终状态，接管默认的自动闭环逻辑。
 */
public class LeaseLifecycleExecutionContext extends LeaseExecutionContext {

    private final LeaseRuntimeClient runtimeClient;
    private final LeaseHandle handle;
    private final AtomicBoolean lifecycleHandled = new AtomicBoolean(false);

    public LeaseLifecycleExecutionContext(LeaseGrant grant,
                                          Runnable heartbeatRequester,
                                          LeaseRuntimeClient runtimeClient) {
        super(grant.getTaskId(),
                grant.getQueue(),
                grant.getTaskType(),
                grant.getPayload(),
                grant.getDeliveryCount(),
                grant.getFailureCount(),
                grant.getAttributes(),
                grant.getCreatedAtMillis(),
                grant.getVisibleAtMillis(),
                grant.getLeaseExpiresAtMillis(),
                heartbeatRequester);
        this.runtimeClient = runtimeClient;
        this.handle = grant.getHandle();
    }

    /**
     * 主动闭环任务
     * <p>
     * 向租约系统提交该任务已完成（成功或失败），调用此方法后，系统通常会更新任务状态并停止续约。
     *
     * @param request 闭环请求参数，包含执行结果（成功/失败）及相关元数据
     * @return 运行时操作结果
     */
    public LeaseRuntimeResult close(LeaseCloseRequest request) {
        LeaseRuntimeResult result = runtimeClient.close(handle, request);
        markLifecycleHandled(result);
        return result;
    }

    /**
     * 主动释放任务
     * <p>
     * 向租约系统主动退还任务，使其在指定的延迟（{@code delayMillis}）后重新可见。
     *
     * @param request 释放请求参数，包含延迟时长及可能更新的任务载荷
     * @return 运行时操作结果
     */
    public LeaseRuntimeResult release(LeaseReleaseRequest request) {
        LeaseRuntimeResult result = runtimeClient.release(handle, request);
        markLifecycleHandled(result);
        return result;
    }

    /**
     * 检查生命周期是否已被显式处理
     */
    boolean isLifecycleHandled() {
        return lifecycleHandled.get();
    }

    private void markLifecycleHandled(LeaseRuntimeResult result) {
        // 当租约操作成功应用（APPLIED）或因某些特殊情况返回 null 时，标记生命周期已完成
        if (result == null || result == LeaseRuntimeResult.APPLIED) {
            lifecycleHandled.set(true);
        }
    }
}
