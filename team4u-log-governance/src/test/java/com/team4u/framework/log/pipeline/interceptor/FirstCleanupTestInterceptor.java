package com.team4u.framework.log.pipeline.interceptor;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.LogInterceptor;

public class FirstCleanupTestInterceptor implements LogInterceptor {

    public static boolean failStop;

    @Override
    public boolean handle(LogEvent event) {
        return true;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void stop() {
        if (failStop) {
            throw new IllegalStateException("test-spi-first");
        }
    }
}
