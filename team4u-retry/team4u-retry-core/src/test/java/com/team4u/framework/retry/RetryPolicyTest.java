package com.team4u.framework.retry;

import com.team4u.framework.retry.policy.RetryPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RetryPolicyTest {

    @Test
    public void testTotalAttempts() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .build();

        RuntimeException ex = new RuntimeException("test");

        Assert.assertTrue(policy.canRetry(1, ex));
        Assert.assertTrue(policy.canRetry(2, ex));
        Assert.assertFalse(policy.canRetry(3, ex));
    }

    @Test
    public void testInfiniteAttempts() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(-1)
                .build();

        RuntimeException ex = new RuntimeException("test");

        Assert.assertTrue(policy.canRetry(1, ex));
        Assert.assertTrue(policy.canRetry(100, ex));
        Assert.assertTrue(policy.canRetry(1000, ex));
    }

    @Test
    public void testLocalAttemptsValidation() {
        try {
            RetryPolicy.builder().maxAttempts(3).localAttempts(4).build();
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("localAttempts"));
        }
    }

    @Test
    public void testConditionExpression() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .condition("attempt <= 2 && message contains 'timeout'")
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
    public void testExtractCompletionExceptionCause() {
        RetryPolicy policy = RetryPolicy.builder()
                .abortOn(IllegalArgumentException.class)
                .build();

        Throwable realCause = new IllegalArgumentException("real cause");
        CompletionException wrappedEx = new CompletionException(realCause);

        Assert.assertFalse(policy.canRetry(1, wrappedEx));
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
