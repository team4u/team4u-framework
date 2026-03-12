package com.team4u.framework.retry.proxy;

import cn.hutool.json.JSONUtil;
import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.domain.store.InvocationArgSnapshot;
import com.team4u.framework.retry.domain.store.InvocationRecoveryData;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyFactory;
import com.team4u.framework.retry.policy.RetryPolicyFactoryRegistry;
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;
import com.team4u.framework.retry.recovery.RecoveryContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class RetryDelegateManagedTest {

    @Before
    public void setUp() {
        RetryPolicyFactoryRegistry.global().unregisterAll();
        RetryPolicyFactoryRegistry.global().register(new RetryPolicyFactory() {
            @Override
            public String key() {
                return "managed-policy";
            }

            @Override
            public RetryPolicy create() {
                return RetryPolicy.builder()
                        .maxRetries(2)
                        .foregroundMaxRetries(0)
                        .backoff(Backoffs.fixed(0L))
                        .retryOn(RuntimeException.class)
                        .build();
            }
        });
    }

    @Test
    public void testManagedVoidSubmissionBuildsRecoveryDataAndStableIdempotencyKey() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedVoidService target = new ManagedVoidService();
        Method method = ManagedVoidService.class.getMethod("replayPayment", String.class, Integer.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        Object firstResult = delegate.executeWithRetry(
                method,
                target,
                new Object[]{"order-1", 3},
                retryable,
                () -> null);
        String firstKey = managedClient.lastSpec.getIdempotencyKey();

        Object secondResult = delegate.executeWithRetry(
                method,
                target,
                new Object[]{"order-1", 3},
                retryable,
                () -> null);

        Assert.assertNull(firstResult);
        Assert.assertNull(secondResult);
        Assert.assertEquals(2, managedClient.submitCount);
        Assert.assertNotNull(managedClient.lastSpec);
        Assert.assertEquals(InvocationReplay.TASK_NAME, managedClient.lastSpec.getRecovery().getTaskType());
        Assert.assertEquals(firstKey, managedClient.lastSpec.getIdempotencyKey());
        Assert.assertFalse(firstKey.isEmpty());

        InvocationRecoveryData payload = JSONUtil.toBean(
                managedClient.lastSpec.getRecovery().getPayload(),
                InvocationRecoveryData.class);
        Assert.assertEquals(ManagedVoidService.class.getName(), payload.getTargetTypeName());
        Assert.assertEquals("replayPayment", payload.getMethodName());
        Assert.assertEquals(2, payload.getArgs().size());
        Assert.assertEquals(String.class.getName(), payload.getArgs().get(0).getTypeName());
        Assert.assertEquals("order-1", payload.getArgs().get(0).getSerializedValue());
    }

    @Test
    public void testManagedSnapshotPreservesNullAndIgnoredParameterPositions() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedIgnoredReferenceService target = new ManagedIgnoredReferenceService();
        Method method = ManagedIgnoredReferenceService.class.getMethod("replayPayment",
                String.class, Input.class, Integer.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        delegate.executeWithRetry(method, target, new Object[]{"order-1", new Input("stream"), null}, retryable, () -> null);

        InvocationRecoveryData payload = JSONUtil.toBean(
                managedClient.lastSpec.getRecovery().getPayload(),
                InvocationRecoveryData.class);
        Assert.assertEquals(3, payload.getArgs().size());

        InvocationArgSnapshot first = payload.getArgs().get(0);
        InvocationArgSnapshot second = payload.getArgs().get(1);
        InvocationArgSnapshot third = payload.getArgs().get(2);

        Assert.assertFalse(first.isIgnored());
        Assert.assertEquals("order-1", first.getSerializedValue());

        Assert.assertTrue(second.isIgnored());
        Assert.assertNull(second.getSerializedValue());

        Assert.assertFalse(third.isIgnored());
        Assert.assertNull(third.getSerializedValue());
    }

    @Test
    public void testManagedVoidSubmissionChangesIdempotencyKeyWhenArgumentsChange() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedVoidService target = new ManagedVoidService();
        Method method = ManagedVoidService.class.getMethod("replayPayment", String.class, Integer.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        delegate.executeWithRetry(method, target, new Object[]{"order-1", 3}, retryable, () -> null);
        String firstKey = managedClient.lastSpec.getIdempotencyKey();

        delegate.executeWithRetry(method, target, new Object[]{"order-2", 3}, retryable, () -> null);
        String secondKey = managedClient.lastSpec.getIdempotencyKey();

        Assert.assertNotEquals(firstKey, secondKey);
    }

    @Test
    public void testManagedIdempotencyKeyIgnoresRetryIgnoreArgumentValue() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedIgnoredReferenceService target = new ManagedIgnoredReferenceService();
        Method method = ManagedIgnoredReferenceService.class.getMethod("replayPayment",
                String.class, Input.class, Integer.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        delegate.executeWithRetry(method, target, new Object[]{"order-1", new Input("stream-1"), 3}, retryable, () -> null);
        String firstKey = managedClient.lastSpec.getIdempotencyKey();

        delegate.executeWithRetry(method, target, new Object[]{"order-1", new Input("stream-2"), 3}, retryable, () -> null);
        String secondKey = managedClient.lastSpec.getIdempotencyKey();

        Assert.assertEquals(firstKey, secondKey);
    }

    @Test
    public void testManagedSnapshotRoundTripsGenericArgumentsThroughInvocationReplay() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedGenericReplayService target = new ManagedGenericReplayService();
        Method method = ManagedGenericReplayService.class.getMethod("replayPayment",
                String.class, Level.class, List.class, Character.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        delegate.executeWithRetry(
                method,
                target,
                new Object[]{"order-1", Level.HIGH, Arrays.asList(new Input("x"), new Input("y")), Character.valueOf('A')},
                retryable,
                () -> null);

        BeanManager.getInstance().registerBean(ManagedGenericReplayService.class.getName(), target);
        new InvocationReplay().recover(
                managedClient.lastSpec.getRecovery().getPayload(),
                RecoveryContext.builder().taskId("task-roundtrip").attempt(1).build());

        Assert.assertEquals("order-1", target.orderId);
        Assert.assertEquals(Level.HIGH, target.level);
        Assert.assertEquals(Character.valueOf('A'), target.initial);
        Assert.assertEquals(2, target.inputs.size());
        Assert.assertEquals("x", target.inputs.get(0).getValue());
        Assert.assertEquals("y", target.inputs.get(1).getValue());
    }

    @Test
    public void testManagedNonVoidMethodRejected() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedStringService target = new ManagedStringService();
        Method method = ManagedStringService.class.getMethod("replayPayment", String.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        try {
            delegate.executeWithRetry(method, target, new Object[]{"order-1"}, retryable, () -> "ok");
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("only supports void return types"));
            Assert.assertTrue(ex.getMessage().contains("ManagedRetryClient.submit"));
        }

        Assert.assertEquals(0, managedClient.submitCount);
    }

    @Test
    public void testManagedAsyncMethodRejected() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedAsyncService target = new ManagedAsyncService();
        Method method = ManagedAsyncService.class.getMethod("replayPayment", String.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        try {
            delegate.executeWithRetry(method, target, new Object[]{"order-1"}, retryable,
                    () -> CompletableFuture.completedFuture("ok"));
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("only supports void return types"));
        }

        Assert.assertEquals(0, managedClient.submitCount);
    }

    @Test
    public void testManagedRetryIgnorePrimitiveRejectedBeforeSubmit() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedIgnoredPrimitiveService target = new ManagedIgnoredPrimitiveService();
        Method method = ManagedIgnoredPrimitiveService.class.getMethod("replayPayment", int.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        try {
            delegate.executeWithRetry(method, target, new Object[]{3}, retryable, () -> null);
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("@RetryIgnore cannot be used on primitive parameters"));
        }

        Assert.assertEquals(0, managedClient.submitCount);
    }

    @Test
    public void testManagedCustomRecoveryRejectedBeforeSubmit() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedCustomRecoveryService target = new ManagedCustomRecoveryService();
        Method method = ManagedCustomRecoveryService.class.getMethod("replayPayment", String.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        try {
            delegate.executeWithRetry(method, target, new Object[]{"order-1"}, retryable, () -> null);
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("only supports InvocationReplay"));
            Assert.assertTrue(ex.getMessage().contains("ManagedRetryClient.submit"));
        }

        Assert.assertEquals(0, managedClient.submitCount);
    }

    @Test
    public void testManagedExistingResultReturnsNullForVoidMethods() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        managedClient.result = new ManagedSubmitResult.Existing<Object>("task-1", RetryStatus.SUCCEEDED, null);
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedVoidService target = new ManagedVoidService();
        Method method = ManagedVoidService.class.getMethod("replayPayment", String.class, Integer.class);

        Object result = delegate.executeWithRetry(
                method,
                target,
                new Object[]{"order-1", 3},
                method.getAnnotation(Retryable.class),
                () -> null);

        Assert.assertNull(result);
        Assert.assertEquals(1, managedClient.submitCount);
    }

    @Test
    public void testManagedModeWithoutManagedClientFailsFast() throws Throwable {
        RetryDelegate delegate = new RetryDelegate(null, null);
        ManagedVoidService target = new ManagedVoidService();
        Method method = ManagedVoidService.class.getMethod("replayPayment", String.class, Integer.class);
        AtomicBoolean proceeded = new AtomicBoolean(false);

        try {
            delegate.executeWithRetry(
                    method,
                    target,
                    new Object[]{"order-1", 1},
                    method.getAnnotation(Retryable.class),
                    () -> {
                        proceeded.set(true);
                        return null;
                    });
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("requires ManagedRetryClient"));
        }

        Assert.assertFalse(proceeded.get());
    }

    public static class ManagedVoidService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED)
        public void replayPayment(String orderId, Integer attempts) {
        }
    }

    public static class ManagedStringService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED)
        public String replayPayment(String orderId) {
            return orderId;
        }
    }

    public static class ManagedAsyncService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED)
        public CompletableFuture<String> replayPayment(String orderId) {
            return CompletableFuture.completedFuture(orderId);
        }
    }

    public static class ManagedIgnoredPrimitiveService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED)
        public void replayPayment(@RetryIgnore int attempts) {
        }
    }

    public static class ManagedCustomRecoveryService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED, recovery = CustomRecoveryHandler.class)
        public void replayPayment(String orderId) {
        }
    }

    public static class ManagedIgnoredReferenceService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED)
        public void replayPayment(String orderId, @RetryIgnore Input body, Integer attempts) {
        }
    }

    public static class ManagedGenericReplayService {
        private String orderId;
        private Level level;
        private List<Input> inputs;
        private Character initial;

        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED)
        public void replayPayment(String orderId, Level level, List<Input> inputs, Character initial) {
            this.orderId = orderId;
            this.level = level;
            this.inputs = inputs;
            this.initial = initial;
        }
    }

    public static class Input {
        private final String value;

        public Input(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public enum Level {
        HIGH
    }

    public static class CustomRecoveryHandler implements RecoveryHandler<InvocationRecoveryData> {
        private static final String TASK_TYPE = "managed-custom-recovery";

        @Override
        public String taskName() {
            return TASK_TYPE;
        }

        @Override
        public void recover(InvocationRecoveryData payload, RecoveryContext context) {
        }
    }

    private static class CapturingManagedRetryClient implements ManagedRetryClient {
        private RetryTaskSpec<?> lastSpec;
        private int submitCount;
        private ManagedSubmitResult<?> result = new ManagedSubmitResult.Accepted<Object>("task-1",
                RetryStatus.WAITING_RETRY, null);

        @Override
        @SuppressWarnings("unchecked")
        public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
            submitCount++;
            lastSpec = spec;
            return (ManagedSubmitResult<T>) result;
        }
    }
}
