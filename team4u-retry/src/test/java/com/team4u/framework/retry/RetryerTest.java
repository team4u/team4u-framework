package com.team4u.framework.retry;

import com.team4u.framework.retry.backoff.Backoff;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
                .maxAttempts(3)
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
                .maxAttempts(3)
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
                .maxAttempts(3)
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

        CompletableFuture<String> resultFuture = retryer.executeAsync(asyncTask, scheduler);

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
                .maxAttempts(2)
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

        CompletableFuture<String> resultFuture = retryer.executeAsync(asyncTask, scheduler);

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
                .maxAttempts(5)
                .build();

        AtomicInteger submitCount = new AtomicInteger(0);
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicInteger delayMs = new AtomicInteger(0);

        TestLeaseBackend backend = new TestLeaseBackend() {
            @Override
            public String saveIntent(String taskType, String payload) {
                return null;
            }

            @Override
            public void completeIntent(String intentId) {
            }


            @Override
            public void markTerminalFailure(String intentId, Throwable cause) {
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
            retryer.execute("task", context -> "{}", () -> {
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

    @Test
    public void testPayloadBuilderReceivesExplicitContext() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2)
                .inMemoryAttempts(1)
                .build();

        AtomicReference<RetryPayloadContext> prepareContext = new AtomicReference<>();
        AtomicReference<RetryPayloadContext> handoffContext = new AtomicReference<>();

        TestLeaseBackend backend = new TestLeaseBackend() {
            @Override
            public String saveIntent(String taskType, String payload) {
                return "intent";
            }

            @Override
            public void completeIntent(String intentId) {
            }


            @Override
            public void markTerminalFailure(String intentId, Throwable cause) {
            }

            @Override
            public void submitForDelay(String intentId, String taskType, String payload, long delay) {
            }
        };

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(RetryDurability.AT_LEAST_ONCE_DURABLE)
                .build();

        try {
            retryer.execute("task", context -> {
                if (context.getPhase() == RetryPayloadContext.Phase.PREPARE_INTENT) {
                    prepareContext.set(context);
                } else {
                    handoffContext.set(context);
                }
                return "{}";
            }, () -> {
                throw new RuntimeException("fail");
            });
            Assert.fail("expected RetryExhaustedException");
        } catch (RetryExhaustedException expected) {
            // expected
        } catch (Exception e) {
            Assert.fail("expected RetryExhaustedException");
        }

        Assert.assertNotNull(prepareContext.get());
        Assert.assertEquals(RetryPayloadContext.Phase.PREPARE_INTENT, prepareContext.get().getPhase());
        Assert.assertEquals(0, prepareContext.get().getExecutedAttempts());

        Assert.assertNotNull(handoffContext.get());
        Assert.assertEquals(RetryPayloadContext.Phase.HANDOFF_TO_BACKEND, handoffContext.get().getPhase());
        Assert.assertEquals(1, handoffContext.get().getExecutedAttempts());
    }

    @Test
    public void testSyncCleanupWithCustomExecutor() throws Exception {
        // [1] 定义策略和自定义线程池
        RetryPolicy policy = RetryPolicy.builder().build();
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService customExecutor = Executors.newSingleThreadExecutor();

        // [2] 模拟后端
        TestLeaseBackend backend = new TestLeaseBackend() {
            @Override
            public String saveIntent(String taskType, String payload) {
                return "intent-123";
            }

            @Override
            public void completeIntent(String intentId) {
                // 模拟 IO 耗时
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                latch.countDown();
            }


            @Override
            public void markTerminalFailure(String intentId, Throwable cause) {
            }

            @Override
            public void submitForDelay(String intentId, String taskType, String payload, long delay) {
            }
        };

        // [3] 构建 Retryer
        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(RetryDurability.AT_LEAST_ONCE_DURABLE)
                .cleanupExecutor(customExecutor)
                .build();

        // [4] 执行成功逻辑
        String result = retryer.execute("test-task", context -> "{}", () -> "success");

        // [5] 验证结果和清理线程池
        Assert.assertEquals("success", result);
        Assert.assertTrue("应当在自定义线程池中异步完成了清理", latch.await(1, TimeUnit.SECONDS));

        customExecutor.shutdown();
    }

    @Test
    public void testAsyncCleanupWithCustomExecutor() throws Exception {
        // [1] 定义策略、调度器和自定义线程池
        RetryPolicy policy = RetryPolicy.builder().build();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        ExecutorService customExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);

        // [2] 模拟后端
        TestLeaseBackend backend = new TestLeaseBackend() {
            @Override
            public String saveIntent(String taskType, String payload) {
                return "intent-async-123";
            }

            @Override
            public void completeIntent(String intentId) {
                latch.countDown();
            }


            @Override
            public void markTerminalFailure(String intentId, Throwable cause) {
            }

            @Override
            public void submitForDelay(String intentId, String taskType, String payload, long delay) {
            }
        };

        // [3] 构建 Retryer
        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(RetryDurability.AT_LEAST_ONCE_DURABLE)
                .cleanupExecutor(customExecutor)
                .build();

        // [4] 执行异步任务
        CompletableFuture<String> future = retryer.executeAsync(
                "task", context -> "{}",
                () -> CompletableFuture.completedFuture("async success"),
                scheduler);

        // [5] 验证
        Assert.assertEquals("async success", future.get(1, TimeUnit.SECONDS));
        Assert.assertTrue("异步任务成功后应当在自定义线程池中完成清理", latch.await(1, TimeUnit.SECONDS));

        scheduler.shutdown();
        customExecutor.shutdown();
    }

    @Test
    public void testBackendHandoffPublishesTaskIds() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2)
                .inMemoryAttempts(1)
                .build();

        java.util.concurrent.atomic.AtomicReference<String> lastIntentId = new java.util.concurrent.atomic.AtomicReference<>();

        TestLeaseBackend backend = new TestLeaseBackend() {
            @Override
            public String saveIntent(String queueName, String contextJson) {
                return null;
            }

            @Override
            public void completeIntent(String intentId) {
            }


            @Override
            public void markTerminalFailure(String intentId, Throwable cause) {
            }

            @Override
            public void submitForDelay(String intentId, String queueName, String contextJson, long delayMs) {
                lastIntentId.set(intentId);
            }
        };

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(RetryDurability.MEMORY_FALLBACK)
                .build();

        String taskType = "test-task";
        String payload = "{\"id\":1}";

        // 第一次执行并失败，触发降级
        try {
            retryer.execute(taskType, context -> payload, () -> {
                throw new RuntimeException("fail");
            });
        } catch (Exception ignored) {
        }

        String id1 = lastIntentId.get();
        Assert.assertNotNull(id1);
        Assert.assertTrue(id1.startsWith("lease-test-"));

        // 第二次执行（相同 taskType 和 payload）仍应成功发布到后端
        try {
            retryer.execute(taskType, context -> payload, () -> {
                throw new RuntimeException("fail");
            });
        } catch (Exception ignored) {
        }

        String id2 = lastIntentId.get();
        Assert.assertNotNull(id2);
        Assert.assertNotEquals("Lease backend 生成的 taskId 不要求稳定复用", id1, id2);

        // 不同 payload 应生成不同 id
        try {
            retryer.execute(taskType, context -> "{\"id\":2}", () -> {
                throw new RuntimeException("fail");
            });
        } catch (Exception ignored) {
        }

        String id3 = lastIntentId.get();
        Assert.assertNotNull(id3);
    }

    @Test(expected = IllegalStateException.class)
    public void testStrongConsistencyFailFastWhenSaveIntentReturnsNull() throws Exception {
        RetryPolicy policy = RetryPolicy.builder().build();
        TestLeaseBackend backend = new TestLeaseBackend() {
            @Override
            public String saveIntent(String queueName, String contextJson) {
                return null;
            }

            @Override
            public void completeIntent(String intentId) {
            }


            @Override
            public void markTerminalFailure(String intentId, Throwable cause) {
            }

            @Override
            public void submitForDelay(String intentId, String queueName, String contextJson, long delayMs) {
            }
        };

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(RetryDurability.AT_LEAST_ONCE_DURABLE)
                .build();

        retryer.execute("task", context -> "{}", () -> "success");
    }

    @Test
    public void testInterruptedDuringBackoffShouldNotFallbackToBackend() throws Exception {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .backoff(Backoff.fixed(5000))
                .build();

        AtomicInteger submitCount = new AtomicInteger(0);
        CountDownLatch firstAttemptStarted = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        TestLeaseBackend backend = new TestLeaseBackend() {
            @Override
            public String saveIntent(String taskType, String payload) {
                return null;
            }

            @Override
            public void completeIntent(String intentId) {
            }


            @Override
            public void markTerminalFailure(String intentId, Throwable cause) {
            }

            @Override
            public void submitForDelay(String intentId, String taskType, String payload, long delay) {
                submitCount.incrementAndGet();
            }
        };

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(RetryDurability.MEMORY_FALLBACK)
                .build();

        Thread worker = new Thread(() -> {
            try {
                retryer.execute("task", context -> "{}", () -> {
                    firstAttemptStarted.countDown();
                    throw new RuntimeException("fail");
                });
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        worker.start();

        Assert.assertTrue("应当进入首次尝试", firstAttemptStarted.await(1, TimeUnit.SECONDS));
        worker.interrupt();
        worker.join(1500);

        Assert.assertTrue("应抛出 InterruptedException", thrown.get() instanceof InterruptedException);
        Assert.assertEquals("中断不应触发后端降级", 0, submitCount.get());
    }

    @Test
    public void testSimpleExecuteFailFastForDurableModes() {
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(2).build();
        TestLeaseBackend backend = new TestLeaseBackend() {
            @Override
            public String saveIntent(String taskType, String payload) {
                return "intent";
            }

            @Override
            public void completeIntent(String intentId) {
            }

            @Override
            public void markTerminalFailure(String intentId, Throwable cause) {
            }

            @Override
            public void submitForDelay(String intentId, String taskType, String payload, long delayMs) {
            }
        };

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(RetryDurability.MEMORY_FALLBACK)
                .build();

        try {
            retryer.execute(() -> "ok");
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("MEMORY_ONLY"));
        } catch (Exception e) {
            Assert.fail("expected IllegalStateException");
        }
    }

    @Test
    public void testSimpleAsyncExecuteFailFastForDurableModes() {
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(2).build();
        TestLeaseBackend backend = new TestLeaseBackend() {
            @Override
            public String saveIntent(String taskType, String payload) {
                return "intent";
            }

            @Override
            public void completeIntent(String intentId) {
            }

            @Override
            public void markTerminalFailure(String intentId, Throwable cause) {
            }

            @Override
            public void submitForDelay(String intentId, String taskType, String payload, long delayMs) {
            }
        };

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .backend(backend)
                .durability(RetryDurability.MEMORY_FALLBACK)
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        try {
            retryer.executeAsync(() -> CompletableFuture.completedFuture("ok"), scheduler);
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("MEMORY_ONLY"));
        } finally {
            scheduler.shutdown();
        }
    }
}
