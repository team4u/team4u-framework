package com.team4u.framework.lease;

import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.runtime.DefaultTaskQueue;
import com.team4u.framework.lease.spi.LeaseBackend;

public final class Leases {

    private Leases() {
    }

    public static TaskQueue queue(LeaseBackend backend, String queueName) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        if (queueName == null || queueName.trim().isEmpty()) {
            throw new IllegalArgumentException("queueName must not be blank");
        }
        return new DefaultTaskQueue(backend, queueName);
    }
}
