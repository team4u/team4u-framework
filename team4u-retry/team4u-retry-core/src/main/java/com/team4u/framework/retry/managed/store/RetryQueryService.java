package com.team4u.framework.retry.managed.store;

import com.team4u.framework.retry.managed.store.record.RetryRecord;

import java.util.Optional;

/**
 * 持久化重试查询服务接口，用于检索重试任务的历史记录及当前状态。
 */
public interface RetryQueryService {

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
}