package com.team4u.framework.lease.spi;

public interface LeaseRuntimeClient {

    LeaseGrant acquire(AcquireCommand command) throws InterruptedException;

    RuntimeResult heartbeat(LeaseHandle handle, long extendMillis);

    RuntimeResult close(LeaseHandle handle, LeaseCompletion completion) throws Exception;

    RuntimeResult release(LeaseHandle handle, LeaseRetry retry) throws Exception;
}
