package com.team4u.framework.retry.proxy;

import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.RetryDurability;
import com.team4u.framework.retry.RetryPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryDelegateTest {

    @Before
    public void setup() {
        RetryPolicyRegistry.global().unregisterAll();
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return "delegate-test";
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder().maxAttempts(1).build();
            }
        });
    }

    @Test
    public void testBuildSnapshotAllowsNullTargetAndArgs() throws Throwable {
        RetryDelegate delegate = new RetryDelegate();
        Method method = DelegateApi.class.getDeclaredMethod("strongConsistency", String.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        MockBackend backend = new MockBackend();
        Object result = delegate.executeWithRetry(
                method,
                null,
                null,
                retryable,
                () -> "ok",
                () -> backend);

        Assert.assertEquals("ok", result);
        Assert.assertEquals(1, backend.saveCount.get());
        Assert.assertTrue("成功后应异步完成 intent 清理", backend.completeLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testMissingBackendGivesActionableError() throws Throwable {
        RetryDelegate delegate = new RetryDelegate();
        Method method = DelegateApi.class.getDeclaredMethod("memoryFallback", String.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        try {
            delegate.executeWithRetry(
                    method,
                    new DelegateApi(),
                    new Object[]{"a"},
                    retryable,
                    () -> "ok",
                    null);
            Assert.fail("预期抛出 IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("MEMORY_FALLBACK"));
            Assert.assertTrue(ex.getMessage().contains("delegate-test"));
            Assert.assertTrue(ex.getMessage().contains("memoryFallback"));
        }
    }

    public static class DelegateApi {
        @Retryable(policy = "delegate-test", durability = RetryDurability.AT_LEAST_ONCE_DURABLE)
        public static String strongConsistency(String value) {
            return value;
        }

        @Retryable(policy = "delegate-test", durability = RetryDurability.MEMORY_FALLBACK)
        public String memoryFallback(String value) {
            return value;
        }
    }

    private static class MockBackend implements RetryBackend {
        AtomicInteger saveCount = new AtomicInteger();
        CountDownLatch completeLatch = new CountDownLatch(1);

        @Override
        public String saveIntent(String queueName, String contextJson) {
            saveCount.incrementAndGet();
            return "intent-1";
        }

        @Override
        public void completeIntent(String intentId) {
            completeLatch.countDown();
        }


        @Override
        public void markTerminalFailure(String intentId, Throwable cause) {
        }

        @Override
        public void submitForDelay(String intentId, String queueName, String contextJson, long delayMs) {
        }
    }
}
