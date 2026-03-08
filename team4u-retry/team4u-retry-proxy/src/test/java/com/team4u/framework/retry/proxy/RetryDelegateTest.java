package com.team4u.framework.retry.proxy;

import cn.hutool.json.JSONUtil;
import com.team4u.framework.retry.RetryHandoffException;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.TestLeaseBackend;
import com.team4u.framework.retry.policy.NamedRetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyRegistry;
import com.team4u.framework.retry.recovery.RecoveryExecutionContext;
import com.team4u.framework.retry.recovery.RetryTaskTypes;
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

/**
 * RetryDelegate 核心逻辑单元测试
 * <p>
 * 验证重试任务快照构建、模式自动推导、任务冻结等核心委托逻辑。
 */
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
                        .localAttempts(1)
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
    public void testPersistentModeBuildsSnapshotBeforeBusinessExecution() throws Throwable {
        RetryDelegate delegate = new RetryDelegate();
        AtomicBoolean serializerCalled = new AtomicBoolean(false);
        AtomicBoolean businessExecuted = new AtomicBoolean(false);
        AtomicInteger serializeCount = new AtomicInteger();
        delegate.setSerializer((parameter, arg) -> {
            Assert.assertFalse("serializer should run before business execution", businessExecuted.get());
            serializerCalled.set(true);
            serializeCount.incrementAndGet();
            return JSONUtil.toJsonStr(String.valueOf(arg));
        });

        Method method = DelegateApi.class.getDeclaredMethod("persistentCall", Object.class);
        Retryable retryable = method.getAnnotation(Retryable.class);
        CapturingBackend backend = new CapturingBackend();

        try {
            delegate.executeWithRetry(
                    method,
                    new DelegateApi(),
                    new Object[]{"payload"},
                    retryable,
                    () -> {
                        businessExecuted.set(true);
                        throw new RuntimeException("fail");
                    },
                    () -> backend);
            Assert.fail("expected RetryHandoffException");
        } catch (RetryHandoffException expected) {
            // expected
        }

        Assert.assertTrue(serializerCalled.get());
        Assert.assertTrue(businessExecuted.get());
        Assert.assertEquals(1, serializeCount.get());
        Assert.assertNotNull(backend.savedPayload);
        Assert.assertNotNull(backend.submittedPayload);
        Assert.assertTrue(backend.savedPayload.contains("payload"));
    }

    @Test
    public void testMissingBackendFallsBackToMemoryMode() throws Throwable {
        RetryDelegate delegate = new RetryDelegate();
        Method method = DelegateApi.class.getDeclaredMethod("persistentCall", Object.class);
        Retryable retryable = method.getAnnotation(Retryable.class);
        AtomicInteger serializeCount = new AtomicInteger();
        delegate.setSerializer((parameter, arg) -> {
            serializeCount.incrementAndGet();
            return JSONUtil.toJsonStr(String.valueOf(arg));
        });

        Object result = delegate.executeWithRetry(
                method,
                new DelegateApi(),
                new Object[]{"a"},
                retryable,
                () -> "ok",
                null);

        Assert.assertEquals("ok", result);
        Assert.assertEquals(0, serializeCount.get());
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
            Assert.fail("expected RetryHandoffException");
        } catch (RetryHandoffException expected) {
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
        Assert.assertEquals(
                DelegateContractImpl.class.getName(),
                JSONUtil.parseObj(backend.savedPayload).getStr("beanName"));
        Assert.assertEquals(
                RetryTaskTypes.DEFAULT_PROXY_RECOVERY,
                JSONUtil.parseObj(backend.savedPayload).getStr("taskType"));
    }

    @Test
    public void testExplicitTaskTypeStillWinsForDurableAnnotation() throws Throwable {
        RetryDelegate delegate = new RetryDelegate();
        Method method = DelegateApi.class.getDeclaredMethod("customTaskType", String.class);
        Retryable retryable = method.getAnnotation(Retryable.class);
        CapturingBackend backend = new CapturingBackend();

        try {
            delegate.executeWithRetry(
                    method,
                    new DelegateApi(),
                    new Object[]{"payload"},
                    retryable,
                    () -> {
                        throw new RuntimeException("fail");
                    },
                    () -> backend);
            Assert.fail("expected RetryHandoffException");
        } catch (RetryHandoffException expected) {
            // expected
        }

        Assert.assertEquals("custom-task", JSONUtil.parseObj(backend.savedPayload).getStr("taskType"));
    }

    @Test
    public void testRecoveringContextSkipsRetryPipeline() throws Throwable {
        RetryDelegate delegate = new RetryDelegate();
        Method method = DelegateApi.class.getDeclaredMethod("persistentCall", Object.class);
        Retryable retryable = method.getAnnotation(Retryable.class);
        AtomicInteger proceedCount = new AtomicInteger();
        CapturingBackend backend = new CapturingBackend();

        RecoveryExecutionContext.enter();
        String result;
        try {
            result = (String) delegate.executeWithRetry(
                    method,
                    new DelegateApi(),
                    new Object[]{"payload"},
                    retryable,
                    () -> {
                        proceedCount.incrementAndGet();
                        return "ok";
                    },
                    () -> backend);
        } finally {
            RecoveryExecutionContext.exit();
        }

        Assert.assertEquals("ok", result);
        Assert.assertEquals(1, proceedCount.get());
        Assert.assertNull(backend.savedPayload);
        Assert.assertNull(backend.submittedPayload);
    }

    public interface DelegateContract {
        @Retryable(policy = "delegate-freeze")
        String durableCall(List<String> value);
    }

    public static class DelegateApi {
        @Retryable(policy = "delegate-test")
        public static String strongConsistency(String value) {
            return value;
        }

        @Retryable(policy = "delegate-test")
        public String memoryOnly(Object value) {
            return String.valueOf(value);
        }

        @Retryable(policy = "delegate-freeze")
        public String persistentCall(Object value) {
            return String.valueOf(value);
        }

        @Retryable(policy = "delegate-freeze", taskType = "custom-task")
        public String customTaskType(String value) {
            return value;
        }
    }

    public static class DelegateContractImpl implements DelegateContract {
        @Override
        public String durableCall(List<String> value) {
            return value.get(0);
        }
    }

    private static class MockBackend extends TestLeaseBackend {
        private final AtomicInteger saveCount = new AtomicInteger();
        private final CountDownLatch completeLatch = new CountDownLatch(1);

        @Override
        public void prepare(com.team4u.framework.retry.backend.RetryTaskSnapshot snapshot) {
            saveCount.incrementAndGet();
            snapshot.setTaskId("intent-1");
        }

        @Override
        public void complete(String taskId) {
            completeLatch.countDown();
        }

        @Override
        public void handoff(String taskId, long delayMillis) {
        }
    }

    private static class CapturingBackend extends TestLeaseBackend {
        private String savedPayload;
        private String submittedPayload;

        @Override
        public void prepare(com.team4u.framework.retry.backend.RetryTaskSnapshot snapshot) {
            this.savedPayload = JSONUtil.toJsonStr(snapshot);
            snapshot.setTaskId("intent-1");
        }

        @Override
        public void complete(String taskId) {
        }

        @Override
        public void handoff(String taskId, long delayMs) {
            this.submittedPayload = savedPayload;
        }
    }
}
