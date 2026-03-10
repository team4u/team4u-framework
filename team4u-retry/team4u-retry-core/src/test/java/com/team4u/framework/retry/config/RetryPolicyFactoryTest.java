package com.team4u.framework.retry.config;

import com.team4u.framework.retry.policy.RetryPolicy;
import org.junit.Assert;
import org.junit.Test;

public class RetryPolicyFactoryTest {

    @Test(expected = IllegalArgumentException.class)
    public void testCreateFailsFastWhenRetryOnExceptionClassMissing() {
        RetryPolicyFactory.create("{\"retryOnExceptions\":[\"com.example.MissingException\"]}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateFailsFastWhenAbortOnExceptionIsNotThrowable() {
        RetryPolicyFactory.create("{\"abortOnExceptions\":[\"java.lang.String\"]}");
    }

    @Test
    public void testCreateMapsForegroundAttempts() {
        RetryPolicy policy = RetryPolicyFactory.create("{\"foregroundMaxAttempts\":2}");
        Assert.assertEquals(Integer.valueOf(2), policy.getForegroundAttempts());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateRejectsLegacyMaxAttemptsKey() {
        RetryPolicyFactory.create("{\"maxAttempts\":3}");
    }
}
