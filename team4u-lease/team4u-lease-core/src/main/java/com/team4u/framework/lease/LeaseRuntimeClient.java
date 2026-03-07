package com.team4u.framework.lease;

/**
 * 运行时租约接口。
 */
public interface LeaseRuntimeClient {

    LeaseGrant acquire(LeaseAcquireRequest request) throws InterruptedException;

    LeaseRuntimeResult ack(LeaseHandle handle);

    LeaseRuntimeResult retry(LeaseHandle handle, long delayMillis, Throwable cause);

    LeaseRuntimeResult fail(LeaseHandle handle, Throwable cause);

    LeaseRuntimeResult heartbeat(LeaseHandle handle, long extendMillis);

    LeaseRuntimeResult release(LeaseHandle handle, long delayMillis);
}
