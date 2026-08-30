package com.team4u.framework.lease.api;

import java.time.Instant;
import java.util.Map;

public interface TaskContext {

    String getTaskId();

    String getQueue();

    String getType();

    String getPayload();

    int getAttemptCount();

    Map<String, String> getAttributes();

    Instant getCreatedAt();

    Instant getVisibleAt();
}
