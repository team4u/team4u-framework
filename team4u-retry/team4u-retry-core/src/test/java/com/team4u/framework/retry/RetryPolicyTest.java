package com.team4u.framework.retry;

import com.team4u.framework.retry.policy.RetryPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RetryPolicyTest {

    @Test
    public void testTotalAttempts() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(2)
                .build();

        RuntimeException ex = new RuntimeException("test");

        Assert.assertTrue(policy.canRetry(1, ex));
        Assert.assertTrue(policy.canRetry(2, ex));
        Assert.assertFalse(policy.canRetry(3, ex));
    }

    @Test
    public void testRetryBudgetSemanticsForZeroOneTwoRetries() {
        RuntimeException ex = new RuntimeException("test");

        RetryPolicy zeroRetries = RetryPolicy.builder().maxRetries(0).build();
        Assert.assertFalse(zeroRetries.canRetry(1, ex));

        RetryPolicy oneRetry = RetryPolicy.builder().maxRetries(1).build();
        Assert.assertTrue(oneRetry.canRetry(1, ex));
        Assert.assertFalse(oneRetry.canRetry(2, ex));

        RetryPolicy twoRetries = RetryPolicy.builder().maxRetries(2).build();
        Assert.assertTrue(twoRetries.canRetry(1, ex));
        Assert.assertTrue(twoRetries.canRetry(2, ex));
        Assert.assertFalse(twoRetries.canRetry(3, ex));
    }

    @Test
    public void testInfiniteAttempts() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(-1)
                .build();

        RuntimeException ex = new RuntimeException("test");

        Assert.assertTrue(policy.canRetry(1, ex));
        Assert.assertTrue(policy.canRetry(100, ex));
        Assert.assertTrue(policy.canRetry(1000, ex));
    }

    @Test
    public void testForegroundRetriesValidation() {
        try {
            RetryPolicy.builder().maxRetries(2).foregroundMaxRetries(3).build();
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("foreground"));
        }
    }

    @Test
    public void testZeroForegroundRetriesIsAllowed() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(2)
                .foregroundMaxRetries(0)
                .build();

        Assert.assertEquals(Integer.valueOf(0), policy.getForegroundMaxRetries());
    }

    @Test
    public void testZeroRetriesAllowsSingleExecutionOnly() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(0)
                .build();

        RuntimeException ex = new RuntimeException("test");

        Assert.assertFalse(policy.canRetry(1, ex));
    }

    @Test
    public void testConditionExpression() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(2)
                .condition("retryCount <= 1 && message contains 'timeout'")
                .build();

        assertTrue(policy.canRetry(1, new RuntimeException("connection timeout")));
        assertFalse(policy.canRetry(3, new RuntimeException("connection timeout")));
        assertFalse(policy.canRetry(1, new RuntimeException("connection reset")));
    }

    @Test
    public void testAbortOnExceptions() {
        RetryPolicy policy = RetryPolicy.builder()
                .abortOn(IllegalArgumentException.class)
                .abortOn(NullPointerException.class)
                .build();

        Assert.assertTrue(policy.canRetry(1, new RuntimeException("test")));
        Assert.assertFalse(policy.canRetry(1, new IllegalArgumentException("bad argument")));
        Assert.assertFalse(policy.canRetry(1, new NullPointerException("null pointer")));

        class CustomIllegalArgumentException extends IllegalArgumentException {
        }
        Assert.assertFalse(policy.canRetry(1, new CustomIllegalArgumentException()));
    }

    @Test
    public void testRetryOnExceptions() {
        RetryPolicy policy = RetryPolicy.builder()
                .retryOn(IOException.class)
                .build();

        Assert.assertTrue(policy.canRetry(1, new IOException("io error")));
        Assert.assertFalse(policy.canRetry(1, new RuntimeException("system error")));
    }

    @Test
    public void testAbortOnWinsOverRetryOnAndCondition() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(3)
                .retryOn(IOException.class)
                .abortOn(java.io.FileNotFoundException.class)
                .condition("retryCount <= 1")
                .build();

        Assert.assertFalse(policy.canRetry(1, new java.io.FileNotFoundException("missing")));
        Assert.assertTrue(policy.canRetry(1, new IOException("io error")));
        Assert.assertTrue(policy.canRetry(2, new IOException("io error")));
        Assert.assertFalse(policy.canRetry(1, new RuntimeException("wrong type")));
    }

    @Test
    public void testExtractCompletionExceptionCause() {
        RetryPolicy policy = RetryPolicy.builder()
                .abortOn(IllegalArgumentException.class)
                .build();

        Throwable realCause = new IllegalArgumentException("real cause");
        CompletionException wrappedEx = new CompletionException(realCause);

        Assert.assertFalse(policy.canRetry(1, wrappedEx));
    }

    @Test
    public void testInterruptedExceptionIsNeverRetried() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(3)
                .retryOn(Exception.class)
                .build();

        try {
            Assert.assertFalse(policy.canRetry(1, new ExecutionException(new InterruptedException("stop"))));
            Assert.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testImmutability() {
        RetryPolicy.Builder builder = RetryPolicy.builder()
                .retryOn(IOException.class);
        RetryPolicy policy = builder.build();

        builder.retryOn(RuntimeException.class);

        Assert.assertTrue(policy.getRetryOnExceptions().contains(IOException.class));
        Assert.assertFalse(policy.getRetryOnExceptions().contains(RuntimeException.class));

        try {
            policy.getRetryOnExceptions().add(IllegalArgumentException.class);
            Assert.fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException ignored) {
        }
    }
}
