package com.team4u.framework.lease.spi;

public final class AcquireCommand {

    private final TaskSubscription subscription;
    private final String workerId;
    private final long leaseMillis;

    private AcquireCommand(TaskSubscription subscription, String workerId, long leaseMillis) {
        if (subscription == null) {
            throw new IllegalArgumentException("subscription must not be null");
        }
        this.subscription = subscription;
        this.workerId = LeaseValues.requireText(workerId, "workerId");
        if (leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be positive");
        }
        this.leaseMillis = leaseMillis;
    }

    public static AcquireCommand of(TaskSubscription subscription, String workerId, long leaseMillis) {
        return new AcquireCommand(subscription, workerId, leaseMillis);
    }

    public TaskSubscription getSubscription() {
        return subscription;
    }

    public String getWorkerId() {
        return workerId;
    }

    public long getLeaseMillis() {
        return leaseMillis;
    }
}
