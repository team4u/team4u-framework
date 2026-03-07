package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.RetryHandoffException;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.TestLeaseBackend;
import com.team4u.framework.retry.policy.NamedRetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyRegistry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * backend presence determines whether proxy retry runs in memory or persistent mode.
 */
public class RetryBackendModeTest {

    @Before
    public void setup() {
        RetryPolicyRegistry.global().unregisterAll();
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return "default";
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder()
                        .maxAttempts(3)
                        .localAttempts(1)
                        .build();
            }
        });
    }

    @Test
    public void testNoBackendRunsInMemoryMode() {
        TestService service = ProxyBuilder.forClass(TestService.class)
                .withDelegate(new TestServiceImpl())
                .addInterceptor(new RetryInterceptor())
                .build();

        try {
            service.failingCall();
            Assert.fail("预期抛出原始异常");
        } catch (RetryHandoffException e) {
            Assert.fail("内存模式不应抛出 RetryHandoffException");
        } catch (RuntimeException e) {
            Assert.assertEquals("fail", e.getMessage());
        }
    }

    @Test
    public void testBackendEnablesPersistentMode() {
        MockBackend backend = new MockBackend();
        TestService service = ProxyBuilder.forClass(TestService.class)
                .withDelegate(new TestServiceImpl())
                .addInterceptor(new RetryInterceptor(backend))
                .build();

        try {
            service.failingCall();
            Assert.fail("预期抛出 RetryHandoffException");
        } catch (RetryHandoffException expected) {
            // expected
        }

        Assert.assertEquals(1, backend.saveCount.get());
        Assert.assertEquals(1, backend.submitCount.get());
        Assert.assertEquals(backend.lastIntentId.get(), backend.lastSubmitIntentId.get());
        Assert.assertEquals(1000, backend.lastDelayMs.get());
        Assert.assertNull("handoff 后应复用 prepared intent，而不是直接 cancel", backend.completedIntentId.get());
    }

    @Test
    public void testBackendEnablesPrepareAndCleanupOnSuccess() throws InterruptedException {
        MockBackend backend = new MockBackend();
        SuccessService service = ProxyBuilder.forClass(SuccessService.class)
                .withDelegate(new SuccessServiceImpl())
                .addInterceptor(new RetryInterceptor(backend))
                .build();

        service.succeed();

        Assert.assertEquals(1, backend.saveCount.get());
        Assert.assertEquals(0, backend.submitCount.get());
        Assert.assertTrue(backend.completeLatch.await(1, TimeUnit.SECONDS));
        Assert.assertEquals(backend.lastIntentId.get(), backend.completedIntentId.get());
    }

    public interface TestService {
        @Retryable
        void failingCall();
    }

    public interface SuccessService {
        @Retryable
        void succeed();
    }

    public static class TestServiceImpl implements TestService {
        @Override
        public void failingCall() {
            throw new RuntimeException("fail");
        }
    }

    public static class SuccessServiceImpl implements SuccessService {
        @Override
        public void succeed() {
        }
    }

    private static class MockBackend extends TestLeaseBackend {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicInteger submitCount = new AtomicInteger();
        AtomicReference<String> lastIntentId = new AtomicReference<>();
        AtomicReference<String> completedIntentId = new AtomicReference<>();
        AtomicReference<String> lastSubmitIntentId = new AtomicReference<>();
        AtomicInteger lastDelayMs = new AtomicInteger();
        CountDownLatch completeLatch = new CountDownLatch(1);

        @Override
        public String saveIntent(String queueName, String contextJson) {
            String id = "intent-" + saveCount.incrementAndGet();
            lastIntentId.set(id);
            return id;
        }

        @Override
        public void completeIntent(String intentId) {
            completedIntentId.set(intentId);
            completeLatch.countDown();
        }

        @Override
        public void markTerminalFailure(String intentId, Throwable cause) {
        }

        @Override
        public void submitForDelay(String intentId, String queueName, String contextJson, long delayMs) {
            submitCount.incrementAndGet();
            lastSubmitIntentId.set(intentId);
            lastDelayMs.set((int) delayMs);
        }
    }
}
