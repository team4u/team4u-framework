package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.handler.LeaseLifecycleAwareTaskHandler;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseReleaseRequest;
import com.team4u.framework.lease.runtime.LeaseLifecycleExecutionContext;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryExecutionContext;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import com.team4u.framework.retry.managed.store.serialize.JsonRetryRecordSerializer;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;
import com.team4u.framework.retry.common.util.RetryExceptionUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * 业务恢复处理器到租约任务处理器的适配器
 * <p>
 * 此适配器通过实现 {@link LeaseLifecycleAwareTaskHandler}，将重试框架的业务恢复逻辑（{@link StringRecoveryHandler}）
 * 映射为租约系统的任务执行逻辑。它负责处理重试状态的持久化映射，并根据 {@link RetryPolicy} 显式控制
 * 租约任务的闭环（SUCCEEDED/FAILED）或带有退避延迟的释放（RELEASE）。
 */
@Slf4j
@Getter
public class RecoveryHandlerLeaseTaskHandlerAdapter implements LeaseLifecycleAwareTaskHandler {

    /**
     * 实际执行恢复业务逻辑的代理处理器
     */
    private final StringRecoveryHandler delegate;

    /**
     * 用于重试记录领域模型的序列化组件
     */
    private final RetryRecordSerializer serializer;

    public RecoveryHandlerLeaseTaskHandlerAdapter(StringRecoveryHandler delegate) {
        this(delegate, JsonRetryRecordSerializer.INSTANCE);
    }

    public RecoveryHandlerLeaseTaskHandlerAdapter(StringRecoveryHandler delegate, RetryRecordSerializer serializer) {
        if (delegate == null) {
            throw new IllegalArgumentException("Lease recovery handler adapter requires StringRecoveryHandler");
        }
        if (serializer == null) {
            throw new IllegalArgumentException("RetryRecordSerializer must not be null");
        }
        this.delegate = delegate;
        this.serializer = serializer;
    }

    @Override
    public void handleLifecycle(LeaseLifecycleExecutionContext context) {
        // 从租约载荷中还原重试记录快照
        RetryRecord record = serializer.deserialize(context.getPayload());

        // 构建恢复上下文，记录当前重试序号等元数据
        RecoveryContext recoveryContext = RecoveryContext.builder()
                .taskId(record.getTaskId())
                // 注意：attempts 表示已完成的尝试，当前由于是从后台拉取处理，逻辑上是 attempts+1 次尝试
                .attempt(record.getState().getAttempts() + 1)
                .build();

        try {
            // 在专用的恢复执行上下文中运行业务代码
            RecoveryExecutionContext.run(
                    () -> delegate.recover(record.getRequest().getRecovery().getPayload(), recoveryContext));

            // 业务执行成功，更新重试领域模型状态
            record.getState().setAttempts(record.getState().getAttempts() + 1);
            record.getState().setStatus(RetryStatus.SUCCEEDED);
            // 清除下次运行时间（当前周期已完成）
            record.getState().setNextRunAt(null);

            // 显式提交租约闭环请求：标记任务为成功，并回传更新后的业务状态载荷
            LeaseRuntimeResult result = context.close(
                    LeaseCloseRequest.builder()
                            .outcome(LeaseTaskOutcome.SUCCEEDED)
                            .payload(serializer.serialize(record))
                            .build());
            assertApplied(result, "closeSucceeded", record.getTaskId());
        } catch (Throwable cause) {
            // 业务执行抛出异常：进入失败退避处理逻辑，由策略决定后续流向
            handleFailure(context, record, RetryExceptionUtil.unwrapAndRestoreInterrupt(cause));
        }
    }

    /**
     * 处理恢复执行失败的情况
     * <p>
     * 根据重试策略（{@link RetryPolicy}）决定是彻底告警失败（CLOSE FAILED），
     * 还是计算退避时间并显式释放（RELEASE）租约以待下次重试。
     */
    private void handleFailure(LeaseLifecycleExecutionContext context, RetryRecord record, Throwable cause) {
        RetryPolicy policy = record.getRequest().getPolicy();
        int failedAttemptsSoFar = record.getState().getAttempts() + 1;

        // 更新失败元数据摘要
        record.getState().setAttempts(failedAttemptsSoFar);
        record.getState().setLastErrorCode(cause.getClass().getSimpleName());
        record.getState().setLastErrorMessage(cause.getMessage());

        // 检查策略：判断是否已达到最大重试次数或触发了不可重试异常
        if (!policy.canRetry(failedAttemptsSoFar, cause)) {
            log.error("Task failed closed (retries exhausted): {}", record.getTaskId(), cause);
            record.getState().setStatus(RetryStatus.FAILED);
            record.getState().setNextRunAt(null);

            // 策略决定不再重试：显式提交租约闭环请求，标记为最终失败
            LeaseRuntimeResult result = context.close(
                    LeaseCloseRequest.builder()
                            .outcome(LeaseTaskOutcome.FAILED)
                            .failureReason(LeaseTaskFailureReason.RETRY_EXHAUSTED)
                            .errorMessage(cause.getMessage())
                            .payload(serializer.serialize(record))
                            .build());
            assertApplied(result, "closeFailed", record.getTaskId());
            return;
        }

        // 策略决定继续重试：计算下一次运行的退避延迟（Backoff）
        long delayMillis = policy.getDelayMillis(failedAttemptsSoFar);
        record.getState().setStatus(RetryStatus.WAITING_RETRY);
        record.getState().setNextRunAt(Instant.now().plusMillis(delayMillis));

        // 显式释放当前租约，并设定可见延迟，使任务在一段时间后重新进入可抢占状态，实现分布式退避重试
        LeaseRuntimeResult result = context.release(
                LeaseReleaseRequest.builder()
                        .delayMillis(delayMillis)
                        .payload(serializer.serialize(record))
                        .errorMessage(cause.getMessage())
                        .build());
        assertApplied(result, "release", record.getTaskId());
    }

    /**
     * 校验租约操作是否成功应用。
     */
    private void assertApplied(LeaseRuntimeResult result, String operation, String taskId) {
        if (result != LeaseRuntimeResult.APPLIED) {
            throw new IllegalStateException(
                    "Failed to " + operation + " lease task: " + taskId + ", result: " + result);
        }
    }
}
