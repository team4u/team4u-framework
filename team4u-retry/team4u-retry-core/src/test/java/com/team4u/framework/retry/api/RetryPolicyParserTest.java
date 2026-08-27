package com.team4u.framework.retry.api;

import com.team4u.framework.retry.api.RetryPolicy;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

import com.team4u.framework.retry.config.RetryPolicyParser;
public class RetryPolicyParserTest {

    @Test
    public void testCreateFailsFastWhenRetryOnExceptionClassMissing() {
        try {
            RetryPolicyParser.create("{\"retryOnExceptions\":[\"com.example.MissingException\"]}");
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals(
                    "Invalid retry policy config. Failed to load retryOnExceptions class:"
                            + " com.example.MissingException",
                    ex.getMessage());
            Assert.assertNotNull(ex.getCause());
        }
    }

    @Test
    public void testCreateFailsFastWhenAbortOnExceptionIsNotThrowable() {
        try {
            RetryPolicyParser.create("{\"abortOnExceptions\":[\"java.lang.String\"]}");
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals(
                    "Invalid retry policy config. abortOnExceptions contains non-Throwable class:"
                            + " java.lang.String",
                    ex.getMessage());
        }
    }

    @Test
    public void testCreateRejectsInvalidBackoffBeforeLoadingRetryOnException() {
        try {
            RetryPolicyParser.create("{"
                    + "\"backoff\":{\"type\":\"unknown\"},"
                    + "\"retryOnExceptions\":[\"com.example.MissingException\"]}");
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals("Unsupported backoff type: unknown", ex.getMessage());
        }
    }

    @Test
    public void testCreateUsesDefaultBackoffAndMaxRetriesForEmptyConfig() {
        RetryPolicy policy = RetryPolicyParser.create("{}");

        Assert.assertEquals(2, policy.getMaxRetries());
        Assert.assertEquals(1000L, policy.getDelayMillis(1));
        Assert.assertEquals(1000L, policy.getDelayMillis(2));
    }

    @Test
    public void testCreateMapsRetryOnAndAbortOnExceptions() {
        RetryPolicy policy = RetryPolicyParser.create("{"
                + "\"backoff\":{\"type\":\"increment\",\"params\":{\"initialDelay\":100,\"stepMillis\":20}},"
                + "\"maxRetries\":5,"
                + "\"retryOnExceptions\":[\"java.lang.IllegalStateException\"],"
                + "\"abortOnExceptions\":[\"java.lang.Error\"]}");

        Assert.assertEquals(Collections.singleton(IllegalStateException.class), policy.getRetryOnExceptions());
        Assert.assertEquals(Collections.singleton(Error.class), policy.getAbortOnExceptions());

        Assert.assertTrue(policy.canRetry(1, new IllegalStateException("retry")));
        Assert.assertFalse(policy.canRetry(1, new IllegalArgumentException("not retryable")));
        Assert.assertFalse(policy.canRetry(1, new OutOfMemoryError("abort")));
        Assert.assertEquals(100L, policy.getDelayMillis(1));
        Assert.assertEquals(120L, policy.getDelayMillis(2));
    }

    @Test
    public void testCreateMapsForegroundRetries() {
        RetryPolicy policy = RetryPolicyParser.create("{\"foregroundMaxRetries\":2}");
        Assert.assertEquals(Integer.valueOf(2), policy.getForegroundMaxRetries());
    }

}
