package com.team4u.framework.retry.client;

import com.team4u.framework.retry.domain.store.RetryRequest;
import com.team4u.framework.retry.store.record.RetryRecord;

/**
 * 重试协调器，负责连接 DurableStore 和实际的执行 Worker。
 * 当任务在前台预算耗尽或直接进入托管时，将由它调度后台 Worker 接管任务执行。
 */
public interface RetryCoordinator {

    /**
     * 将任务交给后端调度中心或 Worker 执行队列
     *
     * @param record      完整的重试记录
     * @param delayMillis 需要延迟执行的时间（毫秒），如需立即执行则为 0
     */
    void schedule(RetryRecord record, long delayMillis);

}
