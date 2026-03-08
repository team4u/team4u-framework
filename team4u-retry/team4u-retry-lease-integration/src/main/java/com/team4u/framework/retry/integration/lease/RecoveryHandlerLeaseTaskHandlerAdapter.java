package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseReleaseRequest;
import com.team4u.framework.lease.runtime.LeaseExecutionContext;
import com.team4u.framework.lease.runtime.LeaseWorker;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.backend.RetryBackend;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.backend.serialize.HutoolRetryTaskSnapshotSerializer;
import com.team4u.framework.retry.backend.serialize.RetryTaskSnapshotSerializer;
import com.team4u.framework.retry.policy.NamedRetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyRegistry;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RetryRecoveryPlanner;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 将 {@link RecoveryHandler} 适配为 {@link LeaseTaskHandler} 的包装类。
 * <p>
 * 使得原有的重试恢复逻辑可以无缝接入 {@link LeaseWorker} 的任务执行流程中。
 */
@Slf4j
@Getter
public class RecoveryHandlerLeaseTaskHandlerAdapter implements LeaseTaskHandler {

    private final RecoveryHandler delegate;
    private final RetryBackend retryBackend;
    private final RetryPolicyRegistry policyRegistry;
    private final RetryRecoveryPlanner planner = new RetryRecoveryPlanner();

    @Setter
    private RetryTaskSnapshotSerializer snapshotSerializer = HutoolRetryTaskSnapshotSerializer.INSTANCE;

    public RecoveryHandlerLeaseTaskHandlerAdapter(RecoveryHandler delegate, RetryBackend retryBackend) {
        this(delegate, retryBackend, RetryPolicyRegistry.global());
    }

    public RecoveryHandlerLeaseTaskHandlerAdapter(RecoveryHandler delegate, RetryBackend retryBackend,
                                                  RetryPolicyRegistry policyRegistry) {
        this.delegate = delegate;
        this.retryBackend = retryBackend;
        this.policyRegistry = policyRegistry;
    }

    @Override
    public void handle(LeaseExecutionContext context) throws Exception {
        RetryTaskSnapshot snapshot = snapshotSerializer.deserialize(context.getPayload());

        try {
            delegate.recover(snapshot);
        } catch (Throwable cause) {
            handleFailure(context, snapshot, cause);
        }
    }

    private void handleFailure(LeaseExecutionContext context, RetryTaskSnapshot snapshot, Throwable cause) {
        // 更新快照进度
        int failedAttempts = snapshot.getExecutedAttempts() + 1;
        snapshot.setExecutedAttempts(failedAttempts);
        snapshot.setLastError(cause.toString());

        RetryPolicy policy = resolvePolicy(snapshot);
        RetryRecoveryPlanner.Plan plan = planner.plan(snapshot, policy, cause);

        if (plan.isRetry()) {
            log.info("Task failed, retrying in {}ms: {}", plan.getDelayMillis(), snapshot.getTaskId());
            retryBackend.saveProgress(snapshot);
            LeaseRuntimeResult result = context.getRuntimeClient().release(
                    context.getHandle(),
                    LeaseReleaseRequest.of(plan.getDelayMillis(), cause == null ? null : cause.toString()));
            checkResult(result, "release", snapshot.getTaskId());
        } else {
            log.error("Task failed closed: {}", snapshot.getTaskId(), cause);
            LeaseRuntimeResult result = context.getRuntimeClient().close(
                    context.getHandle(),
                    LeaseCloseRequest.failed(mapFailureReason(plan.getReason()), plan.getErrorMessage()));
            checkResult(result, "close", snapshot.getTaskId());
        }
    }

    private LeaseTaskFailureReason mapFailureReason(com.team4u.framework.retry.backend.RetryCloseReason reason) {
        if (reason == null) {
            return LeaseTaskFailureReason.ABORTED_BY_POLICY;
        }
        switch (reason) {
            case RETRY_EXHAUSTED:
                return LeaseTaskFailureReason.RETRY_EXHAUSTED;
            case ABORTED_BY_POLICY:
            default:
                return LeaseTaskFailureReason.ABORTED_BY_POLICY;
        }
    }

    private RetryPolicy resolvePolicy(RetryTaskSnapshot snapshot) {
        if (snapshot.getPolicyKey() != null) {
            NamedRetryPolicy namedPolicy = policyRegistry.get(snapshot.getPolicyKey()).orElse(null);
            if (namedPolicy != null) {
                return namedPolicy.getPolicy();
            }
        }
        // 快照中有 maxAttempts 时恢复前台传入的配置，否则使用默认策略
        RetryPolicy.Builder builder = RetryPolicy.builder();
        if (snapshot.getMaxAttempts() > 0) {
            builder.maxAttempts(snapshot.getMaxAttempts());
        }
        return builder.build();
    }

    private void checkResult(LeaseRuntimeResult result, String operation, String taskId) {
        if (result != LeaseRuntimeResult.APPLIED) {
            log.error("Failed to {} lease task: {}, result: {}", operation, taskId, result);
        }
    }
}
