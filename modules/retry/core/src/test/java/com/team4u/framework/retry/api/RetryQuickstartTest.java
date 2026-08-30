package com.team4u.framework.retry.api;

import com.team4u.framework.retry.common.backoff.Backoffs;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryQuickstartTest {

    @Test
    public void inlineQuickstartRetriesUntilSuccess() throws Exception {
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
    public void inlineAsyncQuickstartUsesProvidedScheduler() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CompletableFuture<String> future = Retries.inline()
                    .policy(RetryPolicy.builder()
                            .maxRetries(1)
                            .backoff(Backoffs.fixed(1L))
                            .build())
                    .callAsync(() -> CompletableFuture.completedFuture("async"), scheduler);

            Assert.assertEquals("async", future.get(1L, TimeUnit.SECONDS));
        } finally {
            scheduler.shutdownNow();
        }
    }
}
