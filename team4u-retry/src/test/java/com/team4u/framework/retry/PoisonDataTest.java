package com.team4u.framework.retry;

import com.team4u.framework.retry.exception.RetrySerializationException;
import com.team4u.framework.retry.proxy.serialize.HutoolRetryContextSerializer;
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PoisonDataTest {

    private final RetryBackend mockBackend = new RetryBackend() {
        @Override
        public String saveIntent(String taskType, String payload) {
            return "intentId";
        }

        @Override
        public void completeIntent(String intentId) {
        }

        @Override
        public void submitForDelay(String intentId, String taskType, String payload, long delay) {
        }
    };

    /**
     * 验证 STRONG_CONSISTENCY 模式下的快速失败
     */
    @Test
    public void testStrongConsistencyFailFast() throws Exception {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder().build())
                .backend(mockBackend)
                .durability(RetryDurability.STRONG_CONSISTENCY)
                .build();

        try {
            retryer.execute("task", executedAttempts -> {
                throw new RetrySerializationException("serialization failed");
            }, () -> "ok");
            Assert.fail("预期抛出 IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("强一致性级别要求参数必须可序列化"));
            Assert.assertTrue(e.getCause() instanceof RetrySerializationException);
        }
    }

    /**
     * 验证 MEMORY_FALLBACK 模式下的优雅降级失败
     */
    @Test
    public void testMemoryFallbackGracefulFailure() throws Exception {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder()
                        .maxAttempts(3)
                        .inMemoryAttempts(1) // 强制 1 次内存尝试后降级
                        .build())
                .backend(mockBackend)
                .durability(RetryDurability.MEMORY_FALLBACK)
                .build();

        try {
            retryer.execute("task", executedAttempts -> {
                throw new RetrySerializationException("serialization failed");
            }, () -> {
                throw new RuntimeException("business failed");
            });
            Assert.fail("预期抛出 RetryExhaustedException");
        } catch (RetryExhaustedException e) {
            Assert.assertTrue("错误消息应当包含序列化失败提示", e.getMessage().contains("参数序列化失败导致无法转入后台队列"));
            Assert.assertEquals("business failed", e.getCause().getMessage());
            Assert.assertEquals(1, e.getSuppressed().length);
            Assert.assertTrue(e.getSuppressed()[0] instanceof RetrySerializationException);
        }
    }

    /**
     * 验证 MEMORY_ONLY 模式下完全不涉及序列化（延迟计算的优势）
     */
    @Test
    public void testMemoryOnlyNoSerialization() throws Exception {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder().build())
                .durability(RetryDurability.MEMORY_ONLY)
                .build();

        AtomicBoolean supplierCalled = new AtomicBoolean(false);
        String result = retryer.execute("task", executedAttempts -> {
            supplierCalled.set(true);
            return "{}";
        }, () -> "ok");

        Assert.assertEquals("ok", result);
        Assert.assertFalse("MEMORY_ONLY 不应触发序列化", supplierCalled.get());
    }

    /**
     * 验证异步情况下的快速失败
     */
    @Test
    public void testAsyncStrongConsistencyFailFast() {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder().build())
                .backend(mockBackend)
                .durability(RetryDurability.STRONG_CONSISTENCY)
                .build();

        try {
            retryer.executeAsync("task", executedAttempts -> {
                throw new RetrySerializationException("serialization failed");
            }, () -> CompletableFuture.completedFuture("ok"), Executors.newSingleThreadScheduledExecutor());
            Assert.fail("预期抛出 IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("强一致性级别要求参数必须可序列化"));
        }
    }

    /**
     * 验证异步情况下的降级失败
     */
    @Test
    public void testAsyncMemoryFallbackGracefulFailure() throws Exception {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder()
                        .maxAttempts(3)
                        .inMemoryAttempts(1)
                        .build())
                .backend(mockBackend)
                .durability(RetryDurability.MEMORY_FALLBACK)
                .build();

        CompletableFuture<String> future = retryer.executeAsync("task", executedAttempts -> {
            throw new RetrySerializationException("serialization failed");
        }, () -> {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("business failed"));
            return f;
        }, Executors.newSingleThreadScheduledExecutor());

        try {
            future.get(5, TimeUnit.SECONDS);
            Assert.fail("预期抛出异常");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof RetryExhaustedException);
            Assert.assertTrue(cause.getMessage().contains("参数序列化失败导致无法转入后台队列"));
            Assert.assertEquals(1, cause.getSuppressed().length);
        }
    }

    /**
     * 验证 @RetryIgnore 注解生效
     */
    @Test
    public void testRetryIgnore() throws Exception {
        Method method = TestService.class.getMethod("doWork", String.class, Object.class);
        Parameter[] parameters = method.getParameters();

        HutoolRetryContextSerializer serializer = HutoolRetryContextSerializer.INSTANCE;

        // 第一个参数没注解，正常序列化
        String json1 = serializer.serialize(parameters[0], "hello");
        // Hutool 对于简单字符串的序列化结果可能带引号也可能不带，取决于具体版本和调用方式
        // 这里我们主要验证它不为 null 且是字符串
        Assert.assertNotNull(json1);
        Assert.assertTrue(json1.contains("hello"));

        // 第二个参数有 @RetryIgnore，返回 null
        String json2 = serializer.serialize(parameters[1], new Object());
        Assert.assertNull(json2);
    }

    public interface TestService {
        void doWork(String name, @RetryIgnore Object secret);
    }
}
