package com.team4u.framework.retry.proxy;

import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.exception.RetryHandoffException;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.TestLeaseBackend;
import com.team4u.framework.retry.policy.RetryPolicyFactory;
import com.team4u.framework.retry.policy.RetryPolicyFactoryRegistry;
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.recovery.RetryTaskTypes;
import com.team4u.framework.retry.backend.RetryCloseRequest;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RetryInterceptor 拦截器集成测试
 * <p>
 * 验证基于代理模式的同步/异步方法重试增强逻辑。
 */
public class RetryInterceptorTest {

    @Before
    public void setup() {
        RetryPolicyFactoryRegistry.global().unregisterAll();
        RecoveryHandlerRegistry.global().unregisterAll();
        RetryPolicyFactoryRegistry.global().register(new RetryPolicyFactory() {
            @Override
            public String key() {
                return "rpc-policy";
            }

            @Override
            public RetryPolicy create() {
                return RetryPolicy.builder()
                        .maxAttempts(4)
                        .backoff(Backoffs.fixed(5))
                        .condition("message contains 'timeout' && attempt < 4")
                        .build();
            }
        });
        RetryPolicyFactoryRegistry.global().register(new RetryPolicyFactory() {
            @Override
            public String key() {
                return "ignore-policy";
            }

            @Override
            public RetryPolicy create() {
                return RetryPolicy.builder()
                        .maxAttempts(2)
                        .localAttempts(1)
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
        Assert.assertEquals(3, delegate.syncCount.get());
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
        Assert.assertEquals(3, delegate.asyncCount.get());
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
            Assert.fail("expected exception");
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            Assert.assertEquals("system error", cause.getMessage());
        }

        Assert.assertEquals(1, delegate.nonRetryCount.get());
    }

    @Test
    public void testImplementationMethodParameterAnnotationsAreUsed() {
        CapturingBackend backend = new CapturingBackend();
        IgnoredArgService proxy = ProxyBuilder.forClass(IgnoredArgService.class)
                .withDelegate(new IgnoredArgServiceImpl())
                .addInterceptor(new RetryInterceptor(backend))
                .build();

        try {
            proxy.send("visible", "top-secret");
            Assert.fail("expected RetryHandoffException");
        } catch (RetryHandoffException expected) {
            // expected
        }

        Assert.assertNotNull(backend.submittedPayload);
        Assert.assertTrue(backend.submittedPayload.contains("visible"));
        Assert.assertFalse(backend.submittedPayload.contains("top-secret"));
    }

    @Test
    public void testRetryProxyFactoryCanRegisterDefaultRecoveryHandler() {
        Assert.assertFalse(RecoveryHandlerRegistry.global().get(RetryTaskTypes.DEFAULT_PROXY_RECOVERY).isPresent());

        RetryProxyFactory.registerDefaultRecoveryHandler();

        Assert.assertTrue(RecoveryHandlerRegistry.global().get(RetryTaskTypes.DEFAULT_PROXY_RECOVERY).isPresent());
    }

    public interface OrderService {
        @Retryable(policy = "rpc-policy")
        String createOrderSync(String orderId) throws Exception;

        @Retryable(policy = "rpc-policy")
        CompletableFuture<String> createOrderAsync(String orderId) throws Exception;

        @Retryable(policy = "rpc-policy")
        String nonRetryException(String orderId) throws Exception;
    }

    public interface IgnoredArgService {
        @Retryable(policy = "ignore-policy")
        void send(String name, Object secret);
    }

    public static class OrderServiceImpl implements OrderService {
        private final AtomicInteger syncCount = new AtomicInteger();
        private final AtomicInteger asyncCount = new AtomicInteger();
        private final AtomicInteger nonRetryCount = new AtomicInteger();

        @Override
        public String createOrderSync(String orderId) {
            if (syncCount.incrementAndGet() < 3) {
                throw new RuntimeException("connection timeout");
            }
            return "sync_ok_" + orderId;
        }

        @Override
        public CompletableFuture<String> createOrderAsync(String orderId) {
            CompletableFuture<String> future = new CompletableFuture<>();
            if (asyncCount.incrementAndGet() < 3) {
                future.completeExceptionally(new RuntimeException("read timeout"));
            } else {
                future.complete("async_ok_" + orderId);
            }
            return future;
        }

        @Override
        public String nonRetryException(String orderId) {
            nonRetryCount.incrementAndGet();
            throw new RuntimeException("system error");
        }
    }

    public static class IgnoredArgServiceImpl implements IgnoredArgService {
        @Override
        public void send(String name, @RetryIgnore Object secret) {
            throw new RuntimeException("retry me");
        }
    }

    private static class CapturingBackend extends TestLeaseBackend {
        private String submittedPayload;

        @Override
        public void prepare(RetryTaskSnapshot snapshot) {
            this.submittedPayload = cn.hutool.json.JSONUtil.toJsonStr(snapshot);
            snapshot.setTaskId("intent");
        }

        @Override
        public void close(String taskId, RetryCloseRequest request) {
        }

        @Override
        public void handoff(String taskId, long delayMillis) {
            if (taskId != null) {
                // mock behavior: if taskId is provided, represent it in submittedPayload
            }
        }
    }
}
