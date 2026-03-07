package com.team4u.framework.lease;

/**
 * 运行时租约接口。
 */
public interface LeaseRuntimeClient {

    LeaseGrant acquire(LeaseAcquireRequest request) throws InterruptedException;

    LeaseRuntimeResult ack(String taskId, String workerId, String leaseToken);

    LeaseRuntimeResult retry(String taskId, String workerId, String leaseToken, long delayMillis, Throwable cause);

    LeaseRuntimeResult fail(String taskId, String workerId, String leaseToken, Throwable cause);

    LeaseRuntimeResult heartbeat(String taskId, String workerId, String leaseToken, long extendMillis);
}
