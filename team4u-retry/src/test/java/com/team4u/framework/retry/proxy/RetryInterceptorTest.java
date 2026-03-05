package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.backoff.Backoff;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryInterceptorTest {

    @Before
    public void setup() {
        // 先清空全局策略缓存，避免不同 TestCase 间由于并发注册导致的数据污染
        RetryPolicyRegistry.global().unregisterAll();

        // 每次测试前注册好策略
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return "rpc-policy";
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder()
                        .totalAttempts(4)
                        .backoff(Backoff.fixed(5))
                        // 强大的动态表达式过滤，仅当 message 包含 timeout，且是前 3 次尝试内才能重试
                        .condition("message contains 'timeout' && attempt < 4")
                        .build();
            }
        });
    }

    @Test
    public void testSyncRetry() throws Exception {
        OrderServiceImpl delegate = new OrderServiceImpl();
        OrderService proxy = ProxyBuilder.forClass(OrderService.class)
                .withDelegate(delegate)
                .addInterceptor(new RetryInterceptor())
                .build();

        String result = proxy.createOrderSync("100");
        Assert.assertEquals("sync_ok_100", result);
        Assert.assertEquals("应该失败了2次，在第3次成功，总共执行3次", 3, delegate.syncCount.get());
    }

    @Test
    public void testAsyncRetry() throws Throwable {
        OrderServiceImpl delegate = new OrderServiceImpl();
        OrderService proxy = ProxyBuilder.forClass(OrderService.class)
                .withDelegate(delegate)
                .addInterceptor(new RetryInterceptor())
                .build();

        CompletableFuture<String> future = proxy.createOrderAsync("200");
        String result = future.get(1, TimeUnit.SECONDS);

        Assert.assertEquals("async_ok_200", result);
        Assert.assertEquals("应该失败了2次，在第3次成功，总共执行3次", 3, delegate.asyncCount.get());
    }

    @Test
    public void testFilterByConditionExpression() {
        OrderServiceImpl delegate = new OrderServiceImpl();
        OrderService proxy = ProxyBuilder.forClass(OrderService.class)
                .withDelegate(delegate)
                .addInterceptor(new RetryInterceptor())
                .build();

        try {
            proxy.nonRetryException("300");
            Assert.fail("应该抛出异常");
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            Assert.assertEquals("system error", cause.getMessage());
        }

        Assert.assertEquals("不符合condition，应该没有重试，只执行一次", 1, delegate.nonRetryCount.get());
    }

    // 1. 定义业务接口
    public interface OrderService {
        @Retryable("rpc-policy")
        String createOrderSync(String orderId) throws Exception;

        @Retryable("rpc-policy")
        CompletableFuture<String> createOrderAsync(String orderId) throws Exception;

        @Retryable("rpc-policy")
        String nonRetryException(String orderId) throws Exception;
    }

    // 2. 模拟业务实现
    public static class OrderServiceImpl implements OrderService {
        public AtomicInteger syncCount = new AtomicInteger();
        public AtomicInteger asyncCount = new AtomicInteger();
        public AtomicInteger nonRetryCount = new AtomicInteger();

        @Override
        public String createOrderSync(String orderId) throws Exception {
            if (syncCount.incrementAndGet() < 3) {
                // 抛出带有 timeout 关键字的异常，可以触发 condition 重试
                throw new RuntimeException("connection timeout");
            }
            return "sync_ok_" + orderId;
        }

        @Override
        public CompletableFuture<String> createOrderAsync(String orderId) throws Exception {
            CompletableFuture<String> future = new CompletableFuture<>();
            if (asyncCount.incrementAndGet() < 3) {
                future.completeExceptionally(new RuntimeException("read timeout"));
            } else {
                future.complete("async_ok_" + orderId);
            }
            return future;
        }

        @Override
        public String nonRetryException(String orderId) throws Exception {
            nonRetryCount.incrementAndGet();
            // 不包含 timeout 字眼，依据 expression 定义不会重试
            throw new RuntimeException("system error");
        }
    }
}
