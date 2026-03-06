package com.team4u.framework.lease;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用租约任务模型。
 * <p>
 * 封装了任务在后端存储层中的完整信息，包括元数据、业务负载以及自定义属性。
 */
public class LeaseTask {

    private final String taskId;
    private final String taskType;
    private final String payload;
    private final long createdAtMillis;
    private final long visibleAtMillis;
    private final int attemptCount;
    private final Map<String, String> attributes;

    public LeaseTask(String taskId,
                     String taskType,
                     String payload,
                     long createdAtMillis,
                     long visibleAtMillis,
                     int attemptCount,
                     Map<String, String> attributes) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.payload = payload;
        this.createdAtMillis = createdAtMillis;
        this.visibleAtMillis = visibleAtMillis;
        this.attemptCount = attemptCount;
        this.attributes = attributes == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getPayload() {
        return payload;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getVisibleAtMillis() {
        return visibleAtMillis;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
