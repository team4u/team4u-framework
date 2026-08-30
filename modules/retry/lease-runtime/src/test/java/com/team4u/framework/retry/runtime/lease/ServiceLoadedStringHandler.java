package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;

public class ServiceLoadedStringHandler implements StringRecoveryHandler {
    public static final String TASK_NAME = "service-loaded-string";

    @Override
    public String taskName() {
        return TASK_NAME;
    }

    @Override
    public void recover(String payload, RecoveryContext context) {
    }
}
