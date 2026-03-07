package com.team4u.framework.retry;

import com.team4u.framework.retry.exception.RetrySerializationException;
import com.team4u.framework.retry.proxy.serialize.HutoolRetryContextSerializer;
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 脏数据（异常数据）重试场景测试
 * <p>
 * 重点验证序列化失败、模式切换边界以及各类异常场景下的重试行为。
 */
public class PoisonDataTest {

    private final TestLeaseBackend mockBackend = new TestLeaseBackend() {
        @Override
        public String saveIntent(String taskType, String payload) {
            return "intentId";
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

    /**
     * 验证持久化模式下的快速失败
     */
    @Test
    public void testPersistentModeFailFast() throws Exception {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder().build())
                .backend(mockBackend)
                .build();

        try {
            retryer.execute("task", context -> {
                throw new RetrySerializationException("serialization failed");
            }, () -> "ok");
            Assert.fail("预期抛出 IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("Persistent retry requires serializable arguments"));
            Assert.assertTrue(e.getCause() instanceof RetrySerializationException);
        }
    }

    /**
     * 验证内存模式下完全不涉及序列化
     */
    @Test
    public void testMemoryModeNoSerializationWhenUsingPayloadOverload() throws Exception {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder().build())
                .build();

        AtomicBoolean supplierCalled = new AtomicBoolean(false);
        String result = retryer.execute("task", context -> {
            supplierCalled.set(true);
            return "{}";
        }, () -> "ok");

        Assert.assertEquals("ok", result);
        Assert.assertFalse("内存模式不应触发序列化", supplierCalled.get());
    }

    /**
     * 验证持久化模式下序列化失败发生在业务执行前
     */
    @Test
    public void testPersistentModeSerializationFailsBeforeBusinessExecution() throws Exception {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder()
                        .maxAttempts(3)
                        .localAttempts(1)
                        .build())
                .backend(mockBackend)
                .build();

        AtomicBoolean businessExecuted = new AtomicBoolean(false);
        try {
            retryer.execute("task", context -> {
                throw new RetrySerializationException("serialization failed");
            }, () -> {
                businessExecuted.set(true);
                throw new RuntimeException("business failed");
            });
            Assert.fail("预期抛出 IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("Persistent retry requires serializable arguments"));
            Assert.assertFalse("业务逻辑不应在 prepare 失败后执行", businessExecuted.get());
        }
    }

    /**
     * 验证内存模式下 simple execute 不受 payload builder 影响
     */
    @Test
    public void testMemoryOnlyNoSerialization() throws Exception {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder().build())
                .build();

        AtomicBoolean supplierCalled = new AtomicBoolean(false);
        String result = retryer.execute("task", context -> {
            supplierCalled.set(true);
            return "{}";
        }, () -> "ok");

        Assert.assertEquals("ok", result);
        Assert.assertFalse("内存模式不应触发序列化", supplierCalled.get());
    }

    /**
     * 验证异步情况下的快速失败
     */
    @Test
    public void testAsyncPersistentModeFailFast() {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder().build())
                .backend(mockBackend)
                .build();

        try {
            retryer.executeAsync("task", context -> {
                throw new RetrySerializationException("serialization failed");
            }, () -> CompletableFuture.completedFuture("ok"), Executors.newSingleThreadScheduledExecutor());
            Assert.fail("预期抛出 IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("Persistent retry requires serializable arguments"));
        }
    }

    /**
     * 验证异步持久化模式下 prepare 失败不会执行业务
     */
    @Test
    public void testAsyncPersistentModeSerializationFailsBeforeBusinessExecution() throws Exception {
        Retryer retryer = Retryer.builder()
                .policy(RetryPolicy.builder()
                        .maxAttempts(3)
                        .localAttempts(1)
                        .build())
                .backend(mockBackend)
                .build();

        AtomicBoolean businessExecuted = new AtomicBoolean(false);
        try {
            retryer.executeAsync("task", context -> {
                throw new RetrySerializationException("serialization failed");
            }, () -> {
                businessExecuted.set(true);
                CompletableFuture<String> f = new CompletableFuture<>();
                f.completeExceptionally(new RuntimeException("business failed"));
                return f;
            }, Executors.newSingleThreadScheduledExecutor());
            Assert.fail("预期抛出异常");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("Persistent retry requires serializable arguments"));
            Assert.assertFalse("业务逻辑不应在 prepare 失败后执行", businessExecuted.get());
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
