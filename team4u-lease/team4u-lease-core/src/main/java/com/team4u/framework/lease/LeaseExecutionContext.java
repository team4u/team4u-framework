package com.team4u.framework.lease;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 处理器执行上下文。
 */
public class LeaseExecutionContext {

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
    private final Runnable heartbeatRequester;

    public LeaseExecutionContext(String taskId,
                                 String queue,
                                 String taskType,
                                 String payload,
                                 int deliveryCount,
                                 int failureCount,
                                 Map<String, String> attributes,
                                 long createdAtMillis,
                                 long visibleAtMillis,
                                 long leaseExpiresAtMillis,
                                 Runnable heartbeatRequester) {
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
        this.heartbeatRequester = heartbeatRequester;
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

    public void requestHeartbeat() {
        if (heartbeatRequester != null) {
            heartbeatRequester.run();
        }
    }
}
