package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.RetryDurability;
import com.team4u.framework.retry.RetryPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 重试可靠性级别单元测试
 *
 * @author jay.wu
 */
public class RetryDurabilityTest {

    private MockBackend mockBackend;
    private TestService service;

    @Before
    public void setup() {
        mockBackend = new MockBackend();
        // 注册默认策略
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return "default";
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder()
                        .maxAttempts(3)
                        .inMemoryAttempts(1)
                        .build();
            }
        });

        RetryInterceptor interceptor = new RetryInterceptor(mockBackend);
        service = ProxyBuilder.forClass(TestService.class)
                .withDelegate(new TestServiceImpl())
                .addInterceptor(interceptor)
                .build();
    }

    @Test
    public void testMemoryOnly() {
        try {
            service.memoryOnly();
            Assert.fail("预期抛出原始异常");
        } catch (com.team4u.framework.retry.RetryExhaustedException e) {
            Assert.fail("MEMORY_ONLY 不应抛出 RetryExhaustedException");
        } catch (RuntimeException e) {
            Assert.assertEquals("fail", e.getMessage());
        }
        Assert.assertEquals(0, mockBackend.saveCount.get());
        Assert.assertEquals(0, mockBackend.submitCount.get());
    }

    @Test
    public void testMemoryFallback() {
        try {
            service.memoryFallback();
            Assert.fail("预期抛出 RetryExhaustedException");
        } catch (com.team4u.framework.retry.RetryExhaustedException e) {
            // success
        }
        Assert.assertEquals(0, mockBackend.saveCount.get());
        Assert.assertEquals(1, mockBackend.submitCount.get());
        Assert.assertNotNull("MEMORY_FALLBACK 降级入队 intentId 不应为 null", mockBackend.lastSubmitIntentId.get());
        Assert.assertTrue(mockBackend.lastSubmitIntentId.get().startsWith("rtryh-"));
    }

    @Test
    public void testStrongConsistencySuccess() throws InterruptedException {
        service.strongConsistencySuccess();
        Assert.assertEquals(1, mockBackend.saveCount.get());
        Assert.assertEquals("intent-1", mockBackend.lastIntentId.get());

        // completeIntent 是异步调用的
        Assert.assertTrue(mockBackend.completeLatch.await(1, TimeUnit.SECONDS));
        Assert.assertEquals("intent-1", mockBackend.completedIntentId.get());
    }

    @Test
    public void testStrongConsistencyFailure() {
        try {
            service.strongConsistencyFailure();
            Assert.fail("预期抛出 RetryExhaustedException");
        } catch (com.team4u.framework.retry.RetryExhaustedException e) {
            // success
        }
        Assert.assertEquals(1, mockBackend.saveCount.get());
        Assert.assertEquals(1, mockBackend.submitCount.get());
        Assert.assertEquals("intent-1", mockBackend.lastSubmitIntentId.get());
        Assert.assertEquals(1000, mockBackend.lastDelayMs.get());
    }

    public interface TestService {
        @Retryable(durability = RetryDurability.MEMORY_ONLY)
        void memoryOnly();

        @Retryable(durability = RetryDurability.MEMORY_FALLBACK)
        void memoryFallback();

        @Retryable(durability = RetryDurability.STRONG_CONSISTENCY)
        void strongConsistencySuccess();

        @Retryable(durability = RetryDurability.STRONG_CONSISTENCY)
        void strongConsistencyFailure();
    }

    public static class TestServiceImpl implements TestService {
        @Override
        public void memoryOnly() {
            throw new RuntimeException("fail");
        }

        @Override
        public void memoryFallback() {
            throw new RuntimeException("fail");
        }

        @Override
        public void strongConsistencySuccess() { /* success */ }

        @Override
        public void strongConsistencyFailure() {
            throw new RuntimeException("fail");
        }
    }

    private static class MockBackend implements RetryBackend {
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
        public void submitForDelay(String intentId, String queueName, String contextJson, long delayMs) {
            submitCount.incrementAndGet();
            lastSubmitIntentId.set(intentId);
            lastDelayMs.set((int) delayMs);
        }
    }
}
