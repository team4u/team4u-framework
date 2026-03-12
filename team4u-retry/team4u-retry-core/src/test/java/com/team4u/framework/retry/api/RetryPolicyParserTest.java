package com.team4u.framework.retry.api;

import com.team4u.framework.retry.api.RetryPolicy;
import org.junit.Assert;
import org.junit.Test;

import com.team4u.framework.retry.config.RetryPolicyParser;
public class RetryPolicyParserTest {

    @Test(expected = IllegalArgumentException.class)
    public void testCreateFailsFastWhenRetryOnExceptionClassMissing() {
        RetryPolicyParser.create("{\"retryOnExceptions\":[\"com.example.MissingException\"]}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateFailsFastWhenAbortOnExceptionIsNotThrowable() {
        RetryPolicyParser.create("{\"abortOnExceptions\":[\"java.lang.String\"]}");
    }

    @Test
    public void testCreateMapsForegroundRetries() {
        RetryPolicy policy = RetryPolicyParser.create("{\"foregroundMaxRetries\":2}");
        Assert.assertEquals(Integer.valueOf(2), policy.getForegroundMaxRetries());
    }

}
