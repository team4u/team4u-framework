package com.team4u.framework.retry;

import com.team4u.framework.retry.policy.RetryPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class DepthOptimizationTest {

    @Test(expected = OutOfMemoryError.class)
    public void testErrorShouldNotRetry() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        Retryer retryer = Retryer.with(RetryPolicy.builder()
                .maxAttempts(3)
                .build());

        retryer.execute(() -> {
            count.incrementAndGet();
            throw new OutOfMemoryError("mock oom");
        });

        Assert.assertEquals(1, count.get());
    }

    @Test
    public void testBizExceptionShouldNotBeStripped() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        Retryer retryer = Retryer.with(RetryPolicy.builder()
                .maxAttempts(2)
                .retryOn(BizException.class)
                .build());

        try {
            retryer.execute(() -> {
                count.incrementAndGet();
                throw new RuntimeException(new BizException("biz failed"));
            });
        } catch (RuntimeException ignored) {
        }

        Assert.assertEquals(1, count.get());
    }

    @Test(expected = IllegalStateException.class)
    public void testSimpleExecuteFailsFastWhenBackendConfigured() throws Exception {
        Retryer.builder()
                .policy(RetryPolicy.builder().build())
                .retryBackend(new TestLeaseBackend() {
                    @Override
                    public void prepare(com.team4u.framework.retry.backend.RetryTaskSnapshot snapshot) {
                    }

                    @Override
                    public void handoff(String taskId, long delayMillis) {
                    }

                    @Override
                    public void close(String taskId, com.team4u.framework.retry.backend.RetryCloseRequest request) {
                    }
                })
                .build()
                .execute(() -> "ok");
    }

    public static class BizException extends RuntimeException {
        public BizException(String message) {
            super(message);
        }
    }
}
