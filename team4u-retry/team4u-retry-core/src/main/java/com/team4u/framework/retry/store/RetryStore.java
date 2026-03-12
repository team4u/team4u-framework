package com.team4u.framework.retry.store;

import com.team4u.framework.retry.store.record.*;

import java.util.Optional;

/**
 * 持久化重试领域存储接口，定义了重试任务的完整生命周期管理操作。
 */
public interface RetryStore {

    /**
     * 若任务不存在则创建。利用业务幂等键确保任务唯一性。
     *
     * @param request 创建重试记录的请求信息
     * @return 提交结果，包含状态及关联的重试记录
     */
    SubmitRecord createIfAbsent(RetryCreateRequest request);

    /**
     * 根据内部任务 ID 获取重试记录。
     *
     * @param taskId 内部任务唯一标识
     * @return 包含任务详情及状态的重试记录，若不存在则为空
     */
    Optional<RetryRecord> get(String taskId);

    /**
     * 根据业务任务类型及业务幂等键获取重试记录。
     *
     * @param taskType       任务业务类型
     * @param idempotencyKey 业务层提供的幂等键
     * @return 包含任务详情及状态的重试记录，若不存在则为空
     */
    Optional<RetryRecord> findByIdempotencyKey(String taskType, String idempotencyKey);

    /**
     * 标记任务执行成功，并记录成功详情。
     *
     * @param taskId  内部任务 ID
     * @param success 成功反馈记录，不能为空
     */
    void markSucceeded(String taskId, SuccessRecord success);

    /**
     * 标记任务为最终失败，并记录失败原因。
     *
     * @param taskId  内部任务 ID
     * @param failure 失败详情记录，不能为空
     */
    void markFailed(String taskId, FailureRecord failure);

    /**
     * 标记任务已被取消。
     *
     * @param taskId 内部任务 ID
     * @param cancel 取消详情记录，不能为空
     */
    void markCancelled(String taskId, CancelRecord cancel);

    /**
     * 标记任务当前正在处理中。
     *
     * @param taskId 内部任务 ID
     * @param record 处理中详情记录
     */
    void markProcessing(String taskId, ProcessingRecord record);
}
