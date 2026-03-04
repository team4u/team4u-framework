package com.team4u.framework.retry;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RetryPolicyTest {

    @Test
    public void testMaxAttempts() {
        // 测试最大重试次数限制
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .build();

        RuntimeException ex = new RuntimeException("test");

        Assert.assertTrue("第1次应该可以重试", policy.canRetry(1, ex));
        Assert.assertTrue("第2次应该可以重试", policy.canRetry(2, ex));
        Assert.assertFalse("第3次应该拒绝重试（因为已经达到最大次数）", policy.canRetry(3, ex));
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
}
