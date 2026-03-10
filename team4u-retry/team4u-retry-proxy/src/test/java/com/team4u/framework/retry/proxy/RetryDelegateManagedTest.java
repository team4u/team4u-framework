package com.team4u.framework.retry.proxy;

import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.domain.store.InvocationRecoveryData;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyFactory;
import com.team4u.framework.retry.policy.RetryPolicyFactoryRegistry;
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;
import com.team4u.framework.retry.recovery.RecoveryContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

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
                        .foregroundMaxAttempts(1)
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
        Assert.assertEquals(CustomRecoveryHandler.TASK_TYPE, managedClient.lastSpec.getRecovery().getTaskType());
        Assert.assertEquals(firstKey, managedClient.lastSpec.getIdempotencyKey());
        Assert.assertFalse(firstKey.isEmpty());

        InvocationRecoveryData payload = (InvocationRecoveryData) managedClient.lastSpec.getRecovery().getPayload();
        Assert.assertEquals(ManagedVoidService.class.getName(), payload.getBeanName());
        Assert.assertEquals("replayPayment", payload.getMethodName());
        Assert.assertEquals(2, payload.getArgValues().size());
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

    public static class ManagedVoidService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED, recovery = CustomRecoveryHandler.class)
        public void replayPayment(String orderId, Integer attempts) {
        }
    }

    public static class ManagedStringService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED, recovery = CustomRecoveryHandler.class)
        public String replayPayment(String orderId) {
            return orderId;
        }
    }

    public static class ManagedAsyncService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED, recovery = CustomRecoveryHandler.class)
        public CompletableFuture<String> replayPayment(String orderId) {
            return CompletableFuture.completedFuture(orderId);
        }
    }

    public static class ManagedIgnoredPrimitiveService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED, recovery = CustomRecoveryHandler.class)
        public void replayPayment(@RetryIgnore int attempts) {
        }
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

        @Override
        public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
            submitCount++;
            lastSpec = spec;
            return new ManagedSubmitResult.Accepted<T>("task-1", "SCHEDULED", null);
        }
    }
}
