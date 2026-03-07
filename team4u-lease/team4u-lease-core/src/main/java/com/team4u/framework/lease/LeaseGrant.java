package com.team4u.framework.lease;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表示任务已被某个 worker 成功持有租约。
 */
public class LeaseGrant {

    private final LeaseHandle handle;
    private final String taskId;
    private final String queue;
    private final String taskType;
    private final String payload;
    private final int deliveryCount;
    private final int failureCount;
    private final Map<String, String> attributes;
    private final long createdAtMillis;
    private final long visibleAtMillis;
    private final long leaseExpiresAtMillis;
    private final String workerId;
    private final String leaseToken;

    public LeaseGrant(String taskId,
                      String workerId,
                      String leaseToken,
                      String queue,
                      String taskType,
                      String payload,
                      int deliveryCount,
                      int failureCount,
                      Map<String, String> attributes,
                      long createdAtMillis,
                      long visibleAtMillis,
                      long leaseExpiresAtMillis) {
        this.handle = new LeaseHandle(taskId, workerId, leaseToken);
        this.taskId = taskId;
        this.queue = queue;
        this.taskType = taskType;
        this.payload = payload;
        this.deliveryCount = deliveryCount;
        this.failureCount = failureCount;
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
        this.createdAtMillis = createdAtMillis;
        this.visibleAtMillis = visibleAtMillis;
        this.leaseExpiresAtMillis = leaseExpiresAtMillis;
        this.workerId = workerId;
        this.leaseToken = leaseToken;
    }

    public LeaseHandle getHandle() {
        return handle;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getQueue() {
        return queue;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getPayload() {
        return payload;
    }

    public int getDeliveryCount() {
        return deliveryCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getVisibleAtMillis() {
        return visibleAtMillis;
    }

    public long getLeaseExpiresAtMillis() {
        return leaseExpiresAtMillis;
    }
}
