package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;

public final class LeaseGrant {

    private final LeaseHandle handle;
    private final TaskSnapshot snapshot;

    private LeaseGrant(LeaseHandle handle, TaskSnapshot snapshot) {
        this.handle = requireHandle(handle);
        this.snapshot = snapshot;
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (snapshot.getStatus() != TaskStatus.RUNNING) {
            throw new IllegalArgumentException("snapshot status must be RUNNING");
        }
        if (!handle.getTaskId().equals(snapshot.getTaskId())) {
            throw new IllegalArgumentException("taskId must match snapshot taskId");
        }
        if (!handle.getWorkerId().equals(snapshot.getWorkerId())) {
            throw new IllegalArgumentException("workerId must match snapshot workerId");
        }
    }

    public static LeaseGrant of(LeaseHandle handle, TaskSnapshot snapshot) {
        return new LeaseGrant(handle, snapshot);
    }

    public LeaseHandle getHandle() {
        return handle;
    }

    public TaskSnapshot getSnapshot() {
        return snapshot;
    }

    private static LeaseHandle requireHandle(LeaseHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("handle must not be null");
        }
        return handle;
    }
}
