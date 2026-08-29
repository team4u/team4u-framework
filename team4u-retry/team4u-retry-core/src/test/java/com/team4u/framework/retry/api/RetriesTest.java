package com.team4u.framework.retry.api;

import com.team4u.framework.retry.common.backoff.Backoffs;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RetriesTest {

    @Test
    public void testInlineCallRetriesUntilSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        String result = Retries.inline()
                .policy(RetryPolicy.builder()
                        .maxRetries(2)
                        .backoff(Backoffs.fixed(1L))
                        .retryOn(IllegalStateException.class)
                        .build())
                .call(() -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("temporary failure");
                    }
                    return "ok";
                });

        Assert.assertEquals("ok", result);
        Assert.assertEquals(3, attempts.get());
    }

    @Test
    public void testInlineAsyncDelegatesWithProvidedScheduler() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CompletableFuture<String> future = Retries.inline()
                    .policy(RetryPolicy.builder()
                            .maxRetries(0)
                            .backoff(Backoffs.fixed(0L))
                            .build())
                    .callAsync(() -> CompletableFuture.completedFuture("ok"), scheduler);

            Assert.assertEquals("ok", future.get(1, TimeUnit.SECONDS));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void testInlineDefaultPolicyRetriesUsingDefaultMaxRetries() {
        AtomicInteger attempts = new AtomicInteger();

        try {
            Retries.inline()
                    // 仅验证默认 maxRetries（默认 2 次）；退避用 1ms 避免真实等待默认的 1000ms
                    .policy(RetryPolicy.builder()
                            .backoff(Backoffs.fixed(1L))
                            .build())
                    .call(() -> {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("boom");
                    });
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertEquals("boom", ex.getMessage());
        } catch (Exception ex) {
            Assert.fail("expected IllegalStateException, but got " + ex.getClass().getName());
        }

        Assert.assertEquals(3, attempts.get());
    }
}
