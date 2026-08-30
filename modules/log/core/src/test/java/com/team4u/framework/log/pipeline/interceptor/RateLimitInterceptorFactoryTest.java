package com.team4u.framework.log.pipeline.interceptor;

import com.team4u.framework.log.core.LogEvent;
import org.junit.Assert;
import org.junit.Test;

public class RateLimitInterceptorFactoryTest {

    @Test
    public void stopClearsCountersButPreservesConfiguredSupplier() {
        RateLimitInterceptor interceptor = RateLimitInterceptor.create();
        interceptor.setErrorLimitPerSecond(() -> 1);

        Assert.assertTrue(interceptor.handle(error("same")));
        Assert.assertFalse(interceptor.handle(error("same")));
        interceptor.stop();

        Assert.assertTrue(interceptor.handle(error("same")));
        Assert.assertFalse(interceptor.handle(error("same")));
    }

    @Test
    public void explicitResetRestoresDefaultSupplier() {
        RateLimitInterceptor interceptor = RateLimitInterceptor.create();
        interceptor.setErrorLimitPerSecond(() -> 0);
        interceptor.resetErrorLimitPerSecond();
        interceptor.stop();

        for (int i = 0; i < RateLimitInterceptor.DEFAULT_ERROR_LIMIT_PER_SECOND; i++) {
            Assert.assertTrue(interceptor.handle(error("default-" + i)));
        }
    }

    @Test
    public void negativeSupplierLimitIsClampedToZeroAndSuppressesFirstError() {
        RateLimitInterceptor interceptor = RateLimitInterceptor.create();
        interceptor.setErrorLimitPerSecond(() -> -5);

        LogEvent event = error("negative");
        Assert.assertFalse(interceptor.handle(event));
        Assert.assertTrue(event.isSuppressed());
    }

    @Test
    public void throwingSupplierFallsBackToDefaultLimit() {
        RateLimitInterceptor interceptor = RateLimitInterceptor.create();
        interceptor.setErrorLimitPerSecond(() -> {
            throw new IllegalStateException("broken supplier");
        });
        interceptor.stop();

        String action = "throwing";
        for (int i = 0; i < RateLimitInterceptor.DEFAULT_ERROR_LIMIT_PER_SECOND; i++) {
            Assert.assertTrue(interceptor.handle(error(action)));
        }
        LogEvent overflow = error(action);
        Assert.assertFalse(interceptor.handle(overflow));
        Assert.assertTrue(overflow.isSuppressed());
    }

    private LogEvent error(String action) {
        return new LogEvent().setAction(action).setException(new RuntimeException(action));
    }
}
