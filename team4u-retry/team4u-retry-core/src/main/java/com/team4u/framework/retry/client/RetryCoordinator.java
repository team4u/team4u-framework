package com.team4u.framework.retry.client;

import com.team4u.framework.retry.store.record.RetryRecord;

/**
 * 重试协调中心接口。
 * <p>
 * 协调中心作为持久化层（DurableStore）与实际执行器（Worker）之间的桥梁。
 * 当任务前台重试耗尽、显式提交或由于其它外部触发需要接管时，协调中心负责进行调度。
 * 它的实现通常会涉及外部调度系统（如分布式任务中心）或应用内任务队列。
 */
public interface RetryCoordinator {

    /**
     * 将重试任务提交到后台调度队列。
     * <p>
     * 协调中心将该记录放置于后台执行器可以消费的队列中。
     * 如果存在延迟时间，协调中心应保证任务不会在延迟耗尽前被重试。
     *
     * @param record      包含任务元数据及当前状态的重试记录
     * @param delayMillis 指定任务在后台执行前所需等待的延迟时间（单位：毫秒）
     */
    void schedule(RetryRecord record, long delayMillis);
}
