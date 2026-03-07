package com.team4u.framework.retry;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RetryPolicyTest {

    @Test
    public void testTotalAttempts() {
        // 测试全局总尝试次数限制
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .build();

        RuntimeException ex = new RuntimeException("test");

        Assert.assertTrue("第1次失败后允许继续", policy.canRetry(1, ex));
        Assert.assertTrue("第2次失败后允许继续", policy.canRetry(2, ex));
        Assert.assertFalse("第3次失败后拒绝继续（达到总上限）", policy.canRetry(3, ex));
    }

    @Test
    public void testInfiniteAttempts() {
        // 测试无限次重试
        RetryPolicy policy = RetryPolicy.builder()
                .infiniteAttempts()
                .build();

        RuntimeException ex = new RuntimeException("test");

        Assert.assertTrue(policy.canRetry(1, ex));
        Assert.assertTrue(policy.canRetry(100, ex));
        Assert.assertTrue(policy.canRetry(1000, ex));
    }

    @Test
    public void testLocalAttemptsValidation() {
        try {
            RetryPolicy.builder().maxAttempts(3).localAttempts(4).build();
            Assert.fail("预期抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("localAttempts"));
        }
    }

    @Test
    public void testConditionExpression() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .condition("attempt <= 2 && message contains 'timeout'")
                .build();

        // 尝试次数 <= 2 且 message 包含 timeout，应该重试
        assertTrue(policy.canRetry(1, new RuntimeException("connection timeout")));
        // attempt 不满足 <= 2
        assertFalse(policy.canRetry(3, new RuntimeException("connection timeout")));
        // message 不包含 timeout
        assertFalse(policy.canRetry(1, new RuntimeException("connection reset")));
    }

    @Test
    public void testAbortOnExceptions() {
        // 测试阻断异常机制
        RetryPolicy policy = RetryPolicy.builder()
                .abortOn(IllegalArgumentException.class, NullPointerException.class)
                .build();

        // 未声明的异常允许重试
        Assert.assertTrue(policy.canRetry(1, new RuntimeException("test")));

        // 声明的阻断异常将拒绝重试
        Assert.assertFalse(policy.canRetry(1, new IllegalArgumentException("bad argument")));
        Assert.assertFalse(policy.canRetry(1, new NullPointerException("null pointer")));

        // 声明异常的子类也同样会被阻断
        class CustomIllegalArgumentException extends IllegalArgumentException {
        }
        Assert.assertFalse(policy.canRetry(1, new CustomIllegalArgumentException()));
    }

    @Test
    public void testRetryOnExceptions() {
        // 测试指定支持重试的异常机制
        RetryPolicy policy = RetryPolicy.builder()
                .retryOn(IOException.class)
                .build();

        // 指定了必须得是IOException或其子类才能重试
        Assert.assertTrue(policy.canRetry(1, new IOException("io error")));

        // 未指定的异常将被拒绝重试
        Assert.assertFalse(policy.canRetry(1, new RuntimeException("system error")));
    }

    @Test
    public void testExtractCompletionExceptionCause() {
        // 测试自动剥离 CompletionException 的行为
        RetryPolicy policy = RetryPolicy.builder()
                .abortOn(IllegalArgumentException.class)
                .build();

        // 模拟一个被 CompletionException 包裹的 IllegalArgumentException
        Throwable realCause = new IllegalArgumentException("real cause");
        CompletionException wrappedEx = new CompletionException(realCause);

        // 如果提取原因成功，那么这里应该匹配到 IllegalArgumentException，由于abortOn策略返回false
        Assert.assertFalse("应当能剥离出真实的异常并触发阻断", policy.canRetry(1, wrappedEx));
    }

    @Test
    public void testImmutability() {
        RetryPolicy.Builder builder = RetryPolicy.builder()
                .retryOn(java.io.IOException.class);
        RetryPolicy policy = builder.build();

        // 修改 Builder 不应影响已生成的 Policy
        builder.retryOn(RuntimeException.class);

        Assert.assertTrue("Policy 集合应保持不变", policy.getRetryOnExceptions().contains(java.io.IOException.class));
        Assert.assertFalse("Policy 集合不应包含后续添加的异常", policy.getRetryOnExceptions().contains(RuntimeException.class));

        // 尝试直接修改 Policy 的集合应抛出异常
        try {
            policy.getRetryOnExceptions().add(IllegalArgumentException.class);
            Assert.fail("应抛出 UnsupportedOperationException");
        } catch (UnsupportedOperationException ignored) {
        }
    }
}
