package com.team4u.framework.lease;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面向查询/运维的任务记录。
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseTaskRecord {

    private final String taskId;
    private final String queue;
    private final String taskType;
    private final String payload;
    private final LeaseTaskStatus status;
    private final String workerId;
    private final int priority;
    private final int deliveryCount;
    private final int failureCount;
    private final long createdAtMillis;
    private final long visibleAtMillis;
    private final long leaseExpiresAtMillis;
    private final String lastError;
    private final Map<String, String> attributes;

    public Map<String, String> getAttributes() {
        if (attributes == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }
}
