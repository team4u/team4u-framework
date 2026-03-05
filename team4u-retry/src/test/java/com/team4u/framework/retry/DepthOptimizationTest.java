package com.team4u.framework.retry;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 深度优化验证测试
 * 验证 Error 处理、RuntimeException 剥离保护以及快速失败机制
 */
public class DepthOptimizationTest {

    @Test(expected = OutOfMemoryError.class)
    public void testErrorShouldNotRetry() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        Retryer retryer = Retryer.with(RetryPolicy.builder()
                .maxAttempts(3)
                .build());

        retryer.execute(() -> {
            count.incrementAndGet();
            throw new OutOfMemoryError("模拟内存溢出");
        });

        // 应该直接抛出 Error，不进行任何重试
        Assert.assertEquals(1, count.get());
    }

    @Test
    public void testBizExceptionShouldNotBeStripped() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        // 配置仅对 BizException 重试
        Retryer retryer = Retryer.with(RetryPolicy.builder()
                .maxAttempts(2)
                .retryOn(BizException.class)
                .build());

        try {
            retryer.execute(() -> {
                count.incrementAndGet();
                // 用 RuntimeException 包装业务异常
                throw new RuntimeException(new BizException("业务失败"));
            });
        } catch (RuntimeException e) {
            // 预期结果：由于 RuntimeException 不再被剥离，RetryPolicy 看到的是 RuntimeException
            // 而策略配置的是 retryOn(BizException)，所以不匹配，不会重试。
            // 最终抛出的外层是 RuntimeException。
        }

        Assert.assertEquals(1, count.get());
    }

    @Test(expected = IllegalStateException.class)
    public void testFailFastWhenBackendMissing() {
        Retryer.builder()
                .policy(RetryPolicy.builder().build())
                .durability(RetryDurability.STRONG_CONSISTENCY)
                .backend(null) // 故意不提供后端
                .build();
    }

    /**
     * 模拟业务异常
     */
    public static class BizException extends RuntimeException {
        public BizException(String message) {
            super(message);
        }
    }
}
