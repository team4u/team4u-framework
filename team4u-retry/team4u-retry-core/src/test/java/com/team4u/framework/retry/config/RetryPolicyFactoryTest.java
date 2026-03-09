package com.team4u.framework.retry.config;

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
}
