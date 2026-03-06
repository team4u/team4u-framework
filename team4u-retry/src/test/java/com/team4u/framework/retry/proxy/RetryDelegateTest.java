package com.team4u.framework.retry.proxy;

import cn.hutool.json.JSONUtil;
import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.RetryDurability;
import com.team4u.framework.retry.RetryExhaustedException;
import com.team4u.framework.retry.RetryPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return "delegate-freeze";
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder()
                        .maxAttempts(2)
                        .inMemoryAttempts(1)
                        .build();
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
        Assert.assertTrue(backend.completeLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testMemoryOnlyDoesNotSerializeArguments() throws Throwable {
        RetryDelegate delegate = new RetryDelegate();
        AtomicInteger serializeCount = new AtomicInteger();
        delegate.setSerializer((parameter, arg) -> {
            serializeCount.incrementAndGet();
            throw new AssertionError("MEMORY_ONLY should not serialize arguments");
        });

        Method method = DelegateApi.class.getDeclaredMethod("memoryOnly", Object.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        Object result = delegate.executeWithRetry(
                method,
                new DelegateApi(),
                new Object[]{new Object()},
                retryable,
                () -> "ok",
                null);

        Assert.assertEquals("ok", result);
        Assert.assertEquals(0, serializeCount.get());
    }

    @Test
    public void testMemoryFallbackBuildsSnapshotOnlyWhenHandingOff() throws Throwable {
        RetryDelegate delegate = new RetryDelegate();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        AtomicInteger serializeCount = new AtomicInteger();
        delegate.setSerializer((parameter, arg) -> {
            Assert.assertTrue("serializer should run after business execution fails", proceeded.get());
            serializeCount.incrementAndGet();
            return JSONUtil.toJsonStr(String.valueOf(arg));
        });

        Method method = DelegateApi.class.getDeclaredMethod("memoryFallbackDeferred", Object.class);
        Retryable retryable = method.getAnnotation(Retryable.class);
        CapturingBackend backend = new CapturingBackend();

        try {
            delegate.executeWithRetry(
                    method,
                    new DelegateApi(),
                    new Object[]{"payload"},
                    retryable,
                    () -> {
                        proceeded.set(true);
                        throw new RuntimeException("fail");
                    },
                    () -> backend);
            Assert.fail("expected RetryExhaustedException");
        } catch (RetryExhaustedException expected) {
            // expected
        }

        Assert.assertTrue(proceeded.get());
        Assert.assertEquals(1, serializeCount.get());
        Assert.assertNotNull(backend.submittedPayload);
        Assert.assertTrue(backend.submittedPayload.contains("payload"));
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
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("MEMORY_FALLBACK"));
            Assert.assertTrue(ex.getMessage().contains("delegate-test"));
            Assert.assertTrue(ex.getMessage().contains("memoryFallback"));
        }
    }

    @Test
    public void testFrozenSnapshotKeepsOriginalArgsAndStableCreatedAt() throws Throwable {
        RetryDelegate delegate = new RetryDelegate();
        Method method = DelegateContract.class.getDeclaredMethod("durableCall", List.class);
        Retryable retryable = method.getAnnotation(Retryable.class);
        List<String> args = new ArrayList<>();
        args.add("before");

        CapturingBackend backend = new CapturingBackend();
        try {
            delegate.executeWithRetry(
                    method,
                    new DelegateContractImpl(),
                    new Object[]{args},
                    retryable,
                    () -> {
                        args.clear();
                        args.add("after");
                        throw new RuntimeException("fail");
                    },
                    () -> backend);
            Assert.fail("expected RetryExhaustedException");
        } catch (RetryExhaustedException expected) {
            // expected
        }

        Assert.assertNotNull(backend.savedPayload);
        Assert.assertNotNull(backend.submittedPayload);

        Assert.assertTrue(JSONUtil.parseObj(backend.savedPayload)
                .getJSONArray("argJsonValues")
                .getStr(0)
                .contains("before"));
        Assert.assertFalse(JSONUtil.parseObj(backend.savedPayload)
                .getJSONArray("argJsonValues")
                .getStr(0)
                .contains("after"));
        Assert.assertTrue(JSONUtil.parseObj(backend.submittedPayload)
                .getJSONArray("argJsonValues")
                .getStr(0)
                .contains("before"));
        Assert.assertFalse(JSONUtil.parseObj(backend.submittedPayload)
                .getJSONArray("argJsonValues")
                .getStr(0)
                .contains("after"));
        Assert.assertEquals(
                JSONUtil.parseObj(backend.savedPayload).getLong("createdAt"),
                JSONUtil.parseObj(backend.submittedPayload).getLong("createdAt"));
        Assert.assertEquals(
                JSONUtil.parseObj(backend.savedPayload).getStr("taskId"),
                JSONUtil.parseObj(backend.submittedPayload).getStr("taskId"));
        Assert.assertEquals(
                DelegateContractImpl.class.getName(),
                JSONUtil.parseObj(backend.savedPayload).getStr("beanName"));
    }

    public interface DelegateContract {
        @Retryable(policy = "delegate-freeze", durability = RetryDurability.AT_LEAST_ONCE_DURABLE)
        String durableCall(List<String> value);
    }

    public static class DelegateApi {
        @Retryable(policy = "delegate-test", durability = RetryDurability.AT_LEAST_ONCE_DURABLE)
        public static String strongConsistency(String value) {
            return value;
        }

        @Retryable(policy = "delegate-test", durability = RetryDurability.MEMORY_ONLY)
        public String memoryOnly(Object value) {
            return String.valueOf(value);
        }

        @Retryable(policy = "delegate-test", durability = RetryDurability.MEMORY_FALLBACK)
        public String memoryFallback(String value) {
            return value;
        }

        @Retryable(policy = "delegate-freeze", durability = RetryDurability.MEMORY_FALLBACK)
        public String memoryFallbackDeferred(Object value) {
            return String.valueOf(value);
        }
    }

    public static class DelegateContractImpl implements DelegateContract {
        @Override
        public String durableCall(List<String> value) {
            return value.get(0);
        }
    }

    private static class MockBackend implements RetryBackend {
        private final AtomicInteger saveCount = new AtomicInteger();
        private final CountDownLatch completeLatch = new CountDownLatch(1);

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

    private static class CapturingBackend implements RetryBackend {
        private String savedPayload;
        private String submittedPayload;

        @Override
        public String saveIntent(String queueName, String contextJson) {
            this.savedPayload = contextJson;
            return "intent-1";
        }

        @Override
        public void completeIntent(String intentId) {
        }

        @Override
        public void markTerminalFailure(String intentId, Throwable cause) {
        }

        @Override
        public void submitForDelay(String intentId, String queueName, String contextJson, long delayMs) {
            this.submittedPayload = contextJson;
        }
    }
}
