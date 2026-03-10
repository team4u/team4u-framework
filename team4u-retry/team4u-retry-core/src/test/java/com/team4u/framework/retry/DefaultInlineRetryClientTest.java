package com.team4u.framework.retry;

import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultInlineRetryClient;
import com.team4u.framework.retry.policy.RetryPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultInlineRetryClientTest {

    @Test
    public void testExecuteDoesNotRetryError() {
        AtomicInteger attempts = new AtomicInteger();

        try {
            DefaultInlineRetryClient.getInstance().execute(retryPolicy(), () -> {
                attempts.incrementAndGet();
                throw new OutOfMemoryError("oom");
            });
            Assert.fail("expected OutOfMemoryError");
        } catch (OutOfMemoryError error) {
            Assert.assertEquals("oom", error.getMessage());
        } catch (Exception ex) {
            Assert.fail("expected Error, but got " + ex.getClass().getName());
        }

        Assert.assertEquals(1, attempts.get());
    }

    @Test
    public void testExecuteAsyncDoesNotRetryError() {
        AtomicInteger attempts = new AtomicInteger();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        try {
            CompletableFuture<String> future = DefaultInlineRetryClient.getInstance().executeAsync(
                    retryPolicy(),
                    () -> {
                        attempts.incrementAndGet();
                        throw new OutOfMemoryError("oom");
                    },
                    scheduler);

            try {
                future.join();
                Assert.fail("expected CompletionException");
            } catch (CompletionException ex) {
                Assert.assertTrue(ex.getCause() instanceof OutOfMemoryError);
                Assert.assertEquals("oom", ex.getCause().getMessage());
            }
        } finally {
            scheduler.shutdownNow();
        }

        Assert.assertEquals(1, attempts.get());
    }

    @Test
    public void testExecuteAsyncCancellationCancelsInFlightFuture() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger attempts = new AtomicInteger();
        TrackingCompletableFuture<String> childFuture = new TrackingCompletableFuture<String>();

        try {
            CompletableFuture<String> resultFuture = DefaultInlineRetryClient.getInstance().executeAsync(
                    retryPolicy(),
                    () -> {
                        attempts.incrementAndGet();
                        return childFuture;
                    },
                    scheduler);

            Assert.assertTrue(resultFuture.cancel(true));
            Thread.sleep(50L);

            Assert.assertTrue(resultFuture.isCancelled());
            Assert.assertEquals(1, attempts.get());
            Assert.assertTrue(childFuture.cancelCalled.get());
            Assert.assertTrue(childFuture.isCancelled());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void testExecuteAsyncCancellationSkipsScheduledRetry() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger attempts = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(2)
                .backoff(Backoffs.fixed(200))
                .build();

        try {
            CompletableFuture<String> resultFuture = DefaultInlineRetryClient.getInstance().executeAsync(
                    policy,
                    () -> {
                        attempts.incrementAndGet();
                        CompletableFuture<String> failed = new CompletableFuture<String>();
                        failed.completeExceptionally(new RuntimeException("fail"));
                        return failed;
                    },
                    scheduler);

            Assert.assertTrue(resultFuture.cancel(true));
            Thread.sleep(350L);

            Assert.assertTrue(resultFuture.isCancelled());
            Assert.assertEquals(1, attempts.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void testExecuteHonorsRetryBudgetSemantics() {
        assertExecuteAttempts(0, 1);
        assertExecuteAttempts(1, 2);
        assertExecuteAttempts(2, 3);
    }

    @Test
    public void testExecuteAsyncCancellationCancelsQueuedScheduledRetry() throws Exception {
        TrackingScheduledExecutorService scheduler = new TrackingScheduledExecutorService();
        AtomicInteger attempts = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(2)
                .backoff(Backoffs.fixed(200))
                .build();

        try {
            CompletableFuture<String> resultFuture = DefaultInlineRetryClient.getInstance().executeAsync(
                    policy,
                    () -> {
                        attempts.incrementAndGet();
                        CompletableFuture<String> failed = new CompletableFuture<String>();
                        failed.completeExceptionally(new RuntimeException("fail"));
                        return failed;
                    },
                    scheduler);

            Thread.sleep(50L);
            Assert.assertTrue(resultFuture.cancel(true));
            Thread.sleep(50L);

            Assert.assertTrue(resultFuture.isCancelled());
            Assert.assertEquals(1, attempts.get());
            Assert.assertNotNull(scheduler.lastScheduledFuture);
            Assert.assertTrue(scheduler.lastScheduledFuture.cancelCalled.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private RetryPolicy retryPolicy() {
        return RetryPolicy.builder()
                .maxRetries(2)
                .backoff(Backoffs.fixed(0))
                .build();
    }

    private void assertExecuteAttempts(int maxRetries, int expectedAttempts) {
        AtomicInteger attempts = new AtomicInteger();

        try {
            DefaultInlineRetryClient.getInstance().execute(
                    RetryPolicy.builder()
                            .maxRetries(maxRetries)
                            .backoff(Backoffs.fixed(0L))
                            .retryOn(RuntimeException.class)
                            .build(),
                    () -> {
                        attempts.incrementAndGet();
                        throw new RuntimeException("fail");
                    });
            Assert.fail("expected RuntimeException");
        } catch (RuntimeException ex) {
            Assert.assertEquals("fail", ex.getMessage());
        } catch (Exception ex) {
            Assert.fail("expected RuntimeException, but got " + ex.getClass().getName());
        }

        Assert.assertEquals(expectedAttempts, attempts.get());
    }

    private static class TrackingCompletableFuture<T> extends CompletableFuture<T> {
        private final AtomicBoolean cancelCalled = new AtomicBoolean();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalled.set(true);
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static class TrackingScheduledExecutorService extends java.util.concurrent.ScheduledThreadPoolExecutor {
        private TrackingScheduledFuture lastScheduledFuture;

        private TrackingScheduledExecutorService() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ScheduledFuture<?> delegate = super.schedule(command, delay, unit);
            lastScheduledFuture = new TrackingScheduledFuture(delegate);
            return lastScheduledFuture;
        }
    }

    private static class TrackingScheduledFuture implements ScheduledFuture<Object> {
        private final ScheduledFuture<?> delegate;
        private final AtomicBoolean cancelCalled = new AtomicBoolean();

        private TrackingScheduledFuture(ScheduledFuture<?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return delegate.getDelay(unit);
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed o) {
            return delegate.compareTo(o);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalled.set(true);
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public Object get() throws java.util.concurrent.ExecutionException, InterruptedException {
            return delegate.get();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws java.util.concurrent.ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
            return delegate.get(timeout, unit);
        }
    }
}
