package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.api.LeaseAdminService;
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.api.LeaseProducer;
import com.team4u.framework.lease.api.LeaseQueryService;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.model.*;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.store.RetryDispatcher;
import com.team4u.framework.retry.store.RetryQueryService;
import com.team4u.framework.retry.store.RetryStore;
import com.team4u.framework.retry.store.record.*;
import com.team4u.framework.retry.store.serialize.HutoolRetryRecordSerializer;
import com.team4u.framework.retry.store.serialize.RetryRecordSerializer;
import lombok.Setter;

import java.util.Optional;

/**
 * 通过 lease API 实现 durable retry 逻辑仓储。
 */
public class LeaseDurableRetryStore implements RetryStore, RetryDispatcher, RetryQueryService {

    private static final long PREPARED_INTENT_DELAY_MILLIS = 3650L * 24L * 60L * 60L * 1000L;

    private final LeaseProducer producer;
    private final LeaseAdminService adminService;
    private final LeaseQueryService queryService;
    private final String queue;

    @Setter
    private RetryRecordSerializer serializer = HutoolRetryRecordSerializer.INSTANCE;

    public LeaseDurableRetryStore(LeaseBackend backend) {
        this(backend, backend, backend, RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE);
    }

    public LeaseDurableRetryStore(
            LeaseProducer producer,
            LeaseAdminService adminService,
            LeaseQueryService queryService,
            String queue) {
        this.producer = producer;
        this.adminService = adminService;
        this.queryService = queryService;
        this.queue = (queue == null || queue.trim().isEmpty()) ? RetryLeaseQueues.DEFAULT_RECOVERY_QUEUE : queue;
    }

    @Override
    public SubmitRecord createIfAbsent(RetryCreateRequest request) {
        // 构建重试记录领域模型，此时尚未获得全局 ID
        RetryRecord record = RetryRecord.builder()
                .request(request.getRequest())
                .state(request.getInitialState())
                .build();
        // 通过租约系统的幂等发布接口进行存盘
        LeasePublishResult publishResult = producer.publishIfAbsent(LeasePublishRequest.builder()
                .queue(queue)
                .taskType(request.getRequest().getTaskType())
                .payload(serializer.serialize(record))
                // 租约侧使用“类型|幂等键”作为业务唯一性校验，确保存储层不产生冗余任务
                .businessKey(businessKey(request.getRequest().getTaskType(), request.getRequest().getIdempotencyKey()))
                // 初始意图设为超长延迟，防止任务在未分派前被后台节点误取
                .delayMillis(PREPARED_INTENT_DELAY_MILLIS)
                .build());

        // 若任务已存在，通过反序列化获取当前存储中的最新状态
        RetryRecord resolved = publishResult.getRecord() == null
                ? record
                : deserialize(publishResult.getRecord());

        // 回填持久化生成的任务 ID 到领域模型中
        resolved.setTaskId(publishResult.getTaskId());
        if (resolved.getRequest() != null) {
            resolved.getRequest().setTaskId(publishResult.getTaskId());
        }

        return SubmitRecord.builder()
                .created(publishResult.isCreated())
                .record(resolved)
                .build();
    }

    @Override
    public Optional<RetryRecord> get(String taskId) {
        return queryService.get(taskId).map(this::deserialize);
    }

    @Override
    public Optional<RetryRecord> findByIdempotencyKey(String taskType, String idempotencyKey) {
        return queryService.getByBusinessKey(queue, businessKey(taskType, idempotencyKey)).map(this::deserialize);
    }

    @Override
    public void markSucceeded(String taskId, SuccessRecord success) {
        RetryRecord record = required(taskId);
        record.getState().setStatus(RetryStatus.SUCCEEDED);
        record.getState().setNextRunAt(null);
        // 执行成功后，通过 admin 服务正常关闭租约任务，并持久化最终状态
        assertApplied("closeSucceeded", taskId, adminService.close(taskId, LeaseCloseRequest.builder()
                .outcome(LeaseTaskOutcome.SUCCEEDED)
                .payload(serializer.serialize(record))
                .build()));
    }

    @Override
    public void markFailed(String taskId, FailureRecord failure) {
        RetryRecord record = required(taskId);
        record.getState().setStatus(RetryStatus.FAILED);
        record.getState().setNextRunAt(null);
        record.getState().setLastErrorCode(failure.getErrorCode());
        record.getState().setLastErrorMessage(failure.getErrorMessage());
        // 标记为重试耗尽导致的任务终结
        assertApplied("closeFailed", taskId, adminService.close(taskId, LeaseCloseRequest.builder()
                .outcome(LeaseTaskOutcome.FAILED)
                .failureReason(LeaseTaskFailureReason.RETRY_EXHAUSTED)
                .errorMessage(failure.getErrorMessage())
                .payload(serializer.serialize(record))
                .build()));
    }

    @Override
    public void markCancelled(String taskId, CancelRecord cancel) {
        RetryRecord record = required(taskId);
        record.getState().setStatus(RetryStatus.CANCELLED);
        record.getState().setNextRunAt(null);
        record.getState().setLastErrorMessage(cancel.getReason());
        // 标记为用户取消
        assertApplied("cancel", taskId, adminService.close(taskId, LeaseCloseRequest.builder()
                .outcome(LeaseTaskOutcome.CANCELLED)
                .errorMessage(cancel.getReason())
                .payload(serializer.serialize(record))
                .build()));
    }

    @Override
    public void markWaitingRetry(String taskId, RetryTransition transition) {
        // 注意：WAITING_RETRY 状态切换通常由 dispatch / release 携带 payload 自动完成，此处不做额外控制面写入。
    }

    @Override
    public void markProcessing(String taskId, ProcessingRecord record) {
        // 注意：分布式环境下的 PROCESSING 状态由 Lease 系统自身的 RUNNING 状态隐含表达，减少写放大。
    }

    @Override
    public DispatchResult dispatch(RetryDispatchCommand command) {
        RetryRecord record = required(command.getRecord().getTaskId());
        // 更新内存状态准备持久化
        record.getState().setStatus(RetryStatus.WAITING_RETRY);
        record.getState().setAttempts(command.getTransition().getAttempts());
        record.getState().setNextRunAt(command.getTransition().getNextRunAt());
        record.getState().setLastErrorCode(command.getTransition().getLastErrorCode());
        record.getState().setLastErrorMessage(command.getTransition().getLastErrorMessage());

        // 调用租约系统的重新调度接口，更新载荷的同时设定下一次可见时间（退避延迟）
        assertApplied("updateAndSchedule", record.getTaskId(),
                adminService.updateAndReschedule(LeaseUpdateRequest.builder()
                        .taskId(record.getTaskId())
                        .payload(serializer.serialize(record))
                        .build(), command.getDelayMillis()));

        return DispatchResult.builder()
                .taskId(record.getTaskId())
                .backendTaskId(record.getTaskId())
                .nextRunAt(command.getTransition().getNextRunAt())
                .build();
    }

    private RetryRecord required(String taskId) {
        return get(taskId).orElseThrow(() -> new IllegalStateException("Retry task not found: " + taskId));
    }

    private RetryRecord deserialize(LeaseTaskRecord record) {
        RetryRecord retryRecord = serializer.deserialize(record.getPayload());
        retryRecord.setTaskId(record.getTaskId());
        if (retryRecord.getRequest() != null) {
            retryRecord.getRequest().setTaskId(record.getTaskId());
        }
        if (retryRecord.getState() != null) {
            retryRecord.getState().setBackendTaskId(record.getTaskId());
            if (record.getState() == LeaseTaskState.RUNNING) {
                retryRecord.getState().setStatus(RetryStatus.PROCESSING);
                retryRecord.getState().setNextRunAt(null);
            }
        }
        return retryRecord;
    }

    private String businessKey(String taskType, String idempotencyKey) {
        return taskType + "|" + idempotencyKey;
    }

    private void assertApplied(String operation, String taskId, LeaseAdminResult result) {
        if (result != LeaseAdminResult.APPLIED) {
            throw new LeaseAdminOperationException(operation, taskId, result);
        }
    }
}
