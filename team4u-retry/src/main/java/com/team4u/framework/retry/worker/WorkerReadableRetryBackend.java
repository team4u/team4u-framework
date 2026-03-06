package com.team4u.framework.retry.worker;

import com.team4u.framework.retry.RetryBackend;

/**
 * 可被 Worker 消费的 RetryBackend 扩展接口。
 */
public interface WorkerReadableRetryBackend extends RetryBackend {

    /**
     * 阻塞获取一条已到期可执行任务。
     *
     * @return 任务记录
     * @throws InterruptedException 等待期间被中断
     */
    RetryTaskRecord take() throws InterruptedException;
}
