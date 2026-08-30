package com.team4u.framework.log.core;

import com.team4u.framework.log.pipeline.LogInterceptor;
import com.team4u.framework.log.pipeline.LogInterceptorManager;
import org.junit.Assert;
import org.junit.Test;

public class LogInterceptorManagerCleanupTest {

    @Test
    public void engineResetFailuresStillStopCoreRepositories() {
        LogInterceptorManager manager = new LogInterceptorManager();
        FailingInterceptor first = new FailingInterceptor("first");
        FailingInterceptor second = new FailingInterceptor("second");
        RecordingInterceptor last = new RecordingInterceptor();
        manager.register(first);
        manager.register(second);
        manager.register(last);

        try {
            manager.reset();
            Assert.fail("Expected aggregated reset failure");
        } catch (RuntimeException expected) {
            Assert.assertEquals("first", expected.getMessage());
            Assert.assertEquals(1, expected.getSuppressed().length);
            Assert.assertEquals("second", expected.getSuppressed()[0].getMessage());
        }

        Assert.assertTrue(last.stopped);
    }

    private static final class FailingInterceptor implements LogInterceptor {
        private final String name;

        private FailingInterceptor(String name) {
            this.name = name;
        }

        @Override
        public boolean handle(LogEvent event) {
            return true;
        }

        @Override
        public void stop() {
            throw new IllegalStateException(name);
        }
    }

    private static final class RecordingInterceptor implements LogInterceptor {
        private boolean stopped;

        @Override
        public boolean handle(LogEvent event) {
            return true;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}
