package com.team4u.framework.retry.proxy;

import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.domain.store.InvocationRecoveryData;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyFactory;
import com.team4u.framework.retry.policy.RetryPolicyFactoryRegistry;
import com.team4u.framework.retry.recovery.RecoveryContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

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
                        .maxAttempts(3)
                        .foregroundAttempts(1)
                        .backoff(Backoffs.fixed(0L))
                        .retryOn(RuntimeException.class)
                        .build();
            }
        });
    }

    @Test
    public void testManagedSubmissionBuildsBusinessTaskNameHandlerTypeAndStableIdempotencyKey() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedService target = new ManagedService();
        Method method = ManagedService.class.getMethod("replayPayment", String.class, Integer.class);
        Retryable retryable = method.getAnnotation(Retryable.class);
        Callable<Object> proceedTask = () -> "ok";

        Object firstResult = delegate.executeWithRetry(
                method,
                target,
                new Object[]{"order-1", 3},
                retryable,
                proceedTask);
        String firstKey = managedClient.lastSpec.getIdempotencyKey();

        Object secondResult = delegate.executeWithRetry(
                method,
                target,
                new Object[]{"order-1", 3},
                retryable,
                proceedTask);

        Assert.assertNull(firstResult);
        Assert.assertNull(secondResult);
        Assert.assertNotNull(managedClient.lastSpec);
        Assert.assertEquals(CustomRecoveryHandler.TASK_TYPE, managedClient.lastSpec.getRecovery().getHandlerTaskType());
        Assert.assertEquals(firstKey, managedClient.lastSpec.getIdempotencyKey());
        Assert.assertFalse(firstKey.isEmpty());

        InvocationRecoveryData payload = (InvocationRecoveryData) managedClient.lastSpec.getRecovery().getPayload();
        Assert.assertEquals(ManagedService.class.getName(), payload.getBeanName());
        Assert.assertEquals("replayPayment", payload.getMethodName());
        Assert.assertEquals(2, payload.getArgValues().size());
    }

    @Test
    public void testManagedSubmissionChangesIdempotencyKeyWhenArgumentsChange() throws Throwable {
        CapturingManagedRetryClient managedClient = new CapturingManagedRetryClient();
        RetryDelegate delegate = new RetryDelegate(null, managedClient);
        ManagedService target = new ManagedService();
        Method method = ManagedService.class.getMethod("replayPayment", String.class, Integer.class);
        Retryable retryable = method.getAnnotation(Retryable.class);

        delegate.executeWithRetry(method, target, new Object[]{"order-1", 3}, retryable, () -> "ok");
        String firstKey = managedClient.lastSpec.getIdempotencyKey();

        delegate.executeWithRetry(method, target, new Object[]{"order-2", 3}, retryable, () -> "ok");
        String secondKey = managedClient.lastSpec.getIdempotencyKey();

        Assert.assertNotEquals(firstKey, secondKey);
    }

    public static class ManagedService {
        @Retryable(policy = "managed-policy", mode = RetryMode.MANAGED, recovery = CustomRecoveryHandler.class)
        public String replayPayment(String orderId, Integer attempts) {
            return orderId + ":" + attempts;
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

        @Override
        public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
            lastSpec = spec;
            return new ManagedSubmitResult.Accepted<T>("task-1", "SCHEDULED", null);
        }
    }
}
