package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseReleaseRequest;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.recovery.RecoveryContext;
import com.team4u.framework.retry.recovery.RecoveryExecutionContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.store.record.RetryRecord;
import com.team4u.framework.retry.store.serialize.HutoolRetryRecordSerializer;
import com.team4u.framework.retry.store.serialize.RetryRecordSerializer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * 恢复处理器适配器。
 * <p>
 * 将重试框架的 {@link RecoveryHandler} 适配为租约系统的 {@link LeaseTaskHandler}。
 * 它负责从租约任务载荷中反序列化
 * {@link RetryRecord}，调用对应的恢复处理器，并根据执行结果触发租约的关闭（成功/最终失败）或释放（延迟重试）。
 * </p>
 */
@Slf4j
@Getter
public class RecoveryHandlerLeaseTaskHandlerAdapter implements LeaseTaskHandler {

    /**
     * 实际执行恢复业务逻辑的处理器
     */
    private final RecoveryHandler<String> delegate;

    /**
     * 用于领域模型序列化与反序列化的组件
     */
    @Setter
    private RetryRecordSerializer serializer = HutoolRetryRecordSerializer.INSTANCE;

    public RecoveryHandlerLeaseTaskHandlerAdapter(RecoveryHandler<?> delegate) {
        this.delegate = RecoveryHandlerPayloadTypes.requireStringPayload(delegate,
                "Lease recovery handler adapter");
    }

    @Override
    public void handle(LeaseExecutionContext context) {
        // 从租约载荷中还原重试记录快照
        RetryRecord record = serializer.deserialize(context.getPayload());

        // 构建恢复上下文，包含当前尝试序号等元数据
        RecoveryContext recoveryContext = RecoveryContext.builder()
                .taskId(record.getTaskId())
                // 注意：attempts 表示已完成的尝试，当前由于是从后台拉取处理，逻辑上是 attempts+1 次尝试
                .attempt(record.getState().getAttempts() + 1)
                .build();

        try {
            // 在专用的恢复执行上下文中运行业务代码
            RecoveryExecutionContext.run(
                    () -> delegate.recover(record.getRequest().getRecovery().getPayload(), recoveryContext));

            // 业务执行成功，更新领域模型状态
            record.getState().setAttempts(record.getState().getAttempts() + 1);
            record.getState().setStatus(RetryStatus.SUCCEEDED);
            record.getState().setNextRunAt(null);

            // 通知租约系统：该任务已圆满完成，可以闭环
            LeaseRuntimeResult result = context.getRuntimeClient().close(
                    context.getHandle(),
                    LeaseCloseRequest.builder()
                            .outcome(LeaseTaskOutcome.SUCCEEDED)
                            .payload(serializer.serialize(record))
                            .build());
            assertApplied(result, "closeSucceeded", record.getTaskId());

            // 标记此任务生命周期已由本处理器接管并完成，防止 LeaseWorker 重复处理
            context.markLifecycleHandled();
        } catch (Throwable cause) {
            // 业务执行抛出异常，进入失败退避处理逻辑
            handleFailure(context, record, cause);
        }
    }

    /**
     * 处理恢复执行失败的情况。
     * <p>
     * 根据重试策略（{@link RetryPolicy}）决定是彻底告警失败，还是计算退避时间并释放租约以待下次重试。
     * </p>
     */
    private void handleFailure(LeaseExecutionContext context, RetryRecord record, Throwable cause) {
        RetryPolicy policy = record.getRequest().getPolicy();
        int attempts = record.getState().getAttempts() + 1;

        // 更新失败元数据
        record.getState().setAttempts(attempts);
        record.getState().setLastErrorCode(cause.getClass().getSimpleName());
        record.getState().setLastErrorMessage(cause.getMessage());

        // 检查策略：是否还能继续重试
        if (!policy.canRetry(attempts, cause)) {
            log.error("Task failed closed (retries exhausted): {}", record.getTaskId(), cause);
            record.getState().setStatus(RetryStatus.FAILED);
            record.getState().setNextRunAt(null);

            // 策略决定不再重试：标记租约任务为最终失败并关闭
            LeaseRuntimeResult result = context.getRuntimeClient().close(
                    context.getHandle(),
                    LeaseCloseRequest.builder()
                            .outcome(LeaseTaskOutcome.FAILED)
                            .failureReason(LeaseTaskFailureReason.RETRY_EXHAUSTED)
                            .errorMessage(cause.getMessage())
                            .payload(serializer.serialize(record))
                            .build());
            assertApplied(result, "closeFailed", record.getTaskId());
            context.markLifecycleHandled();
            return;
        }

        // 策略决定继续重试：计算下一次运行的延迟时间（退避算法）
        long delayMillis = policy.getDelayMillis(attempts);
        record.getState().setStatus(RetryStatus.WAITING_RETRY);
        record.getState().setNextRunAt(Instant.now().plusMillis(delayMillis));

        // 释放当前租约，并设定可见延迟，使任务在一段时间后重新进入就绪队列
        LeaseRuntimeResult result = context.getRuntimeClient().release(
                context.getHandle(),
                LeaseReleaseRequest.builder()
                        .delayMillis(delayMillis)
                        .payload(serializer.serialize(record))
                        .errorMessage(cause.getMessage())
                        .build());
        assertApplied(result, "release", record.getTaskId());
        context.markLifecycleHandled();
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
