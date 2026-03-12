package com.team4u.framework.retry.common.concurrent;

import org.junit.Assert;
import org.junit.Test;

public class RetryExecutorManagerTest {

    @Test
    public void testShutdownHookEnabledByDefault() {
        String previous = System.getProperty(RetryExecutorManager.SHUTDOWN_HOOK_ENABLED_PROPERTY);
        try {
            System.clearProperty(RetryExecutorManager.SHUTDOWN_HOOK_ENABLED_PROPERTY);
            Assert.assertTrue(RetryExecutorManager.isShutdownHookEnabled());
        } finally {
            restore(previous);
        }
    }

    @Test
    public void testShutdownHookCanBeDisabledByProperty() {
        String previous = System.getProperty(RetryExecutorManager.SHUTDOWN_HOOK_ENABLED_PROPERTY);
        try {
            System.setProperty(RetryExecutorManager.SHUTDOWN_HOOK_ENABLED_PROPERTY, "false");
            Assert.assertFalse(RetryExecutorManager.isShutdownHookEnabled());
        } finally {
            restore(previous);
        }
    }

    @Test
    public void testInstanceManagerCanBeCreatedWithoutShutdownHook() {
        RetryExecutorManager manager = new RetryExecutorManager(false);
        try {
            Assert.assertNotNull(manager.getScheduler());
            Assert.assertNotNull(manager.getCleanupExecutor());
        } finally {
            manager.shutdown();
        }
    }

    private void restore(String previous) {
        if (previous == null) {
            System.clearProperty(RetryExecutorManager.SHUTDOWN_HOOK_ENABLED_PROPERTY);
        } else {
            System.setProperty(RetryExecutorManager.SHUTDOWN_HOOK_ENABLED_PROPERTY, previous);
        }
    }
}
