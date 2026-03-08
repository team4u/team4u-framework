package com.team4u.framework.lease;

import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.model.LeaseAcquireRequest;
import com.team4u.framework.lease.model.LeaseGrant;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeaseSubscription;

public abstract class AbstractLeaseContractSupport {

    protected static final String DEFAULT_QUEUE = "default";

    protected abstract LeaseBackend createBackend();

    protected String publish(LeaseBackend backend, String taskType, String payload) {
        return publish(backend, taskType, payload, 0L);
    }

    protected String publish(LeaseBackend backend, String taskType, String payload, long delayMillis) {
        return backend.publish(LeasePublishRequest.builder()
                .queue(DEFAULT_QUEUE)
                .taskType(taskType)
                .payload(payload)
                .delayMillis(delayMillis)
                .build());
    }

    protected LeaseGrant acquire(LeaseBackend backend, String workerId, long leaseMillis,
                                 long waitTimeoutMillis) throws Exception {
        return backend.acquire(LeaseAcquireRequest.builder()
                .workerId(workerId)
                .leaseMillis(leaseMillis)
                .waitTimeoutMillis(waitTimeoutMillis)
                .subscription(LeaseSubscription.builder().queue(DEFAULT_QUEUE).build())
                .build());
    }

    protected LeaseGrant acquire(LeaseBackend backend, String workerId, long leaseMillis,
                                 long waitTimeoutMillis, String... ignoredTaskTypes) throws Exception {
        return acquire(backend, workerId, leaseMillis, waitTimeoutMillis);
    }
}
