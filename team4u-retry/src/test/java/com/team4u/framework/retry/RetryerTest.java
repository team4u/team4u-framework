package com.team4u.framework.retry;

import com.team4u.framework.retry.backoff.Backoff;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class RetryerTest {

    @Test
    public void testSyncExecuteSuccessInFirstTry() throws Exception {
        // 测试同步执行：首次就成功的情况
        RetryPolicy policy = RetryPolicy.builder().build();
        Retryer retryer = Retryer.with(policy);

        String result = retryer.execute(() -> "success");
        Assert.assertEquals("success", result);
    }

    @Test
    public void testSyncExecuteSuccessAfterRetries() throws Exception {
        // 测试同步执行：失败若干次后成功
        RetryPolicy policy = RetryPolicy.builder()
                .totalAttempts(3)
                .backoff(Backoff.fixed(10)) // 为了测试速度，使用很小的延迟
                .build();

        Retryer retryer = Retryer.with(policy);
        AtomicInteger counter = new AtomicInteger();

        String result = retryer.execute(() -> {
            if (counter.incrementAndGet() < 3) {
                // 前两次抛出异常模拟失败
                throw new RuntimeException("fail");
            }
            return "success";
        });

        Assert.assertEquals("success", result);
        Assert.assertEquals(3, counter.get());
    }

    @Test(expected = RuntimeException.class)
    public void testSyncExecuteExceedTotalAttempts() throws Exception {
        // 测试同步执行：超过全局总尝试次数抛出异常
        RetryPolicy policy = RetryPolicy.builder()
                .totalAttempts(3)
                .backoff(Backoff.fixed(5))
                .build();

        Retryer retryer = Retryer.with(policy);

        // 这将会在执行3次后抛出最后的异常
        retryer.execute(() -> {
            throw new RuntimeException("always fail");
        });
    }

    @Test
    public void testAsyncExecuteSuccessAfterRetries() throws Exception {
        // 测试异步执行：失败若干次后成功
        RetryPolicy policy = RetryPolicy.builder()
                .totalAttempts(3)
                .backoff(Backoff.fixed(10))
                .build();

        Retryer retryer = Retryer.with(policy);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        AtomicInteger counter = new AtomicInteger();

        Supplier<CompletableFuture<String>> asyncTask = () -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            if (counter.incrementAndGet() < 3) {
                future.completeExceptionally(new RuntimeException("async fail"));
            } else {
                future.complete("async success");
            }
            return future;
        };

        CompletableFuture<String> resultFuture = retryer.executeAsync("test-task", "{}", asyncTask, scheduler);

        // 等待异步结果
        String result = resultFuture.get(1, TimeUnit.SECONDS);

        Assert.assertEquals("async success", result);
        Assert.assertEquals(3, counter.get());

        scheduler.shutdown();
    }

    @Test
    public void testAsyncExecuteExceedTotalAttempts() throws Exception {
        // 测试异步执行：超过全局总尝试次数失败
        RetryPolicy policy = RetryPolicy.builder()
                .totalAttempts(2)
                .backoff(Backoff.fixed(5))
                .build();

        Retryer retryer = Retryer.with(policy);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        AtomicInteger counter = new AtomicInteger();

        Supplier<CompletableFuture<String>> asyncTask = () -> {
            counter.incrementAndGet();
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("always fail async"));
            return future;
        };

        CompletableFuture<String> resultFuture = retryer.executeAsync("test-task", "{}", asyncTask, scheduler);

        try {
            resultFuture.get(1, TimeUnit.SECONDS);
            Assert.fail("预期应该抛出执行异常");
        } catch (ExecutionException e) {
            // 剥离掉外层的ExecutionException，拿到原始异常比对
            Assert.assertEquals(RuntimeException.class, e.getCause().getClass());
            Assert.assertEquals("always fail async", e.getCause().getMessage());
        }

        Assert.assertEquals("应当执行了2次", 2, counter.get());
    }

    @Test
    public void testPersistentDurabilityDefaultInMemoryAttempts() {
        RetryPolicy policy = RetryPolicy.builder()
                .totalAttempts(5)
                .build();

        AtomicInteger submitCount = new AtomicInteger(0);
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicInteger delayMs = new AtomicInteger(0);

        RetryBackend backend = new RetryBackend() {
            @Override
            public String saveIntent(String taskType, String payload) {
                return null;
            }

            @Override
            public void completeIntent(String intentId) {
            }

            @Override
            public void submitForDelay(String intentId, String taskType, String payload, long delay) {
                submitCount.incrementAndGet();
                delayMs.set((int) delay);
            }
        };

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(RetryDurability.MEMORY_FALLBACK)
                .build();

        try {
            retryer.execute("task", "{}", () -> {
                callCount.incrementAndGet();
                throw new RuntimeException("always fail");
            });
            Assert.fail("预期抛出 RetryExhaustedException");
        } catch (RetryExhaustedException ex) {
            // expected
        } catch (Exception e) {
            Assert.fail("预期抛出 RetryExhaustedException");
        }

        Assert.assertEquals("持久化模式默认前台只尝试2次", 2, callCount.get());
        Assert.assertEquals("应提交一次到后端", 1, submitCount.get());
        Assert.assertEquals("后端下一次应为第3次尝试对应延迟", 1000, delayMs.get());

    }
}
