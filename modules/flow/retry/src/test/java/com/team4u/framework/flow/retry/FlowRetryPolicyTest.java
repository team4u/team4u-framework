package com.team4u.framework.flow.retry;

import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Metadata;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.test.FlowAssertions;
import com.team4u.framework.retry.api.NamedRetryPolicyFactory;
import com.team4u.framework.retry.api.NamedRetryPolicyRegistry;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoff;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.config.RetryPolicyParser;
import com.team4u.framework.retry.dynamic.DynamicRetryPolicyRegistry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Flow 重试策略适配层单元测试
 *
 * @author jay.wu
 */
public class FlowRetryPolicyTest {

    @After
    public void tearDown() {
        DynamicRetryPolicyRegistry.reset();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderRequest {
        private String orderId;
        private int amount;
    }

    private PolicyContext createMockContext(int attempt, Cancellation cancellation) {
        return new PolicyContext() {
            @Override
            public Metadata metadata() {
                return new Metadata("flow-test", 1, "exec-1", "$.step1", Optional.empty());
            }

            @Override
            public int attempt() {
                return attempt;
            }

            @Override
            public Cancellation.Signal cancellation() {
                return cancellation != null ? cancellation.signal() : Cancellation.create().signal();
            }
        };
    }

    @Test
    public void testFixedBackoff() {
        FlowRetryPolicy<OrderRequest> policy = FlowRetries.fixed(3, 50);
        assertEquals(3, policy.resolveMaxAttempts());
        assertEquals(50, policy.resolveBackoff().calculateMillis(1));
        assertEquals(50, policy.resolveBackoff().calculateMillis(2));

        AtomicInteger callCount = new AtomicInteger(0);
        Flow<OrderRequest, String> step = Flow.step((ctx, req) -> {
            if (callCount.incrementAndGet() < 2) {
                return Outcome.failed(Failure.of("RPC_ERROR", "Temporary network blip"));
            }
            return Outcome.accepted("success:" + req.getOrderId());
        });

        // 标准治理洋葱模型：Retry 在外，Policy 在内（每次重试均经过 Policy 准入与退避评估）
        Flow<OrderRequest, String> flow = policy.wrap(step, Function.identity());

        long start = System.currentTimeMillis();
        FlowResult<String> result = Local.compile(flow).run(new OrderRequest("1001", 10));
        long duration = System.currentTimeMillis() - start;

        FlowAssertions.assertAccepted(result, "success:1001");
        assertEquals(2, callCount.get());
        assertTrue("Duration should reflect fixed backoff delay: " + duration, duration >= 40);
    }

    @Test
    public void testExponentialBackoff() {
        FlowRetryPolicy<OrderRequest> policy = FlowRetries.exponential(4, 30, 2.0, 200);
        Backoff backoff = policy.resolveBackoff();
        assertEquals(30, backoff.calculateMillis(1));
        assertEquals(60, backoff.calculateMillis(2));
        assertEquals(120, backoff.calculateMillis(3));
        assertEquals(200, backoff.calculateMillis(4));

        AtomicInteger callCount = new AtomicInteger(0);
        Flow<OrderRequest, String> step = Flow.step((ctx, req) -> {
            if (callCount.incrementAndGet() < 3) {
                return Outcome.failed(Failure.of("TIMEOUT", "Downstream timeout"));
            }
            return Outcome.accepted("done:" + req.getOrderId());
        });

        Flow<OrderRequest, String> flow = policy.wrap(step, Function.identity());

        long start = System.currentTimeMillis();
        FlowResult<String> result = Local.compile(flow).run(new OrderRequest("1002", 20));
        long duration = System.currentTimeMillis() - start;

        FlowAssertions.assertAccepted(result, "done:1002");
        assertEquals(3, callCount.get());
        // 30ms (after attempt 1) + 60ms (after attempt 2) = ~90ms
        assertTrue("Duration should reflect exponential backoff delay sum: " + duration, duration >= 75);
    }

    @Test
    public void testExponentialJitterBackoff() {
        FlowRetryPolicy<OrderRequest> policy = FlowRetries.jitter(3, 40, 2.0, 200);
        Backoff backoff = policy.resolveBackoff();
        for (int i = 1; i <= 3; i++) {
            long delay = backoff.calculateMillis(i);
            assertTrue(delay >= 40);
            assertTrue(delay <= 200);
        }

        AtomicInteger callCount = new AtomicInteger(0);
        Flow<OrderRequest, String> step = Flow.step((ctx, req) -> {
            if (callCount.incrementAndGet() < 2) {
                return Outcome.failed(Failure.of("JITTER_ERR", "Flaky call"));
            }
            return Outcome.accepted("ok:" + req.getOrderId());
        });

        Flow<OrderRequest, String> flow = policy.wrap(step, Function.identity());

        FlowResult<String> result = Local.compile(flow).run(new OrderRequest("1003", 30));
        FlowAssertions.assertAccepted(result, "ok:1003");
        assertEquals(2, callCount.get());
    }

    @Test
    public void testIncrementBackoff() {
        Backoff backoff = Backoffs.increment(40, 20);
        FlowRetryPolicy<OrderRequest> policy = FlowRetries.of(3, backoff);
        assertEquals(40, policy.resolveBackoff().calculateMillis(1));
        assertEquals(60, policy.resolveBackoff().calculateMillis(2));
        assertEquals(80, policy.resolveBackoff().calculateMillis(3));

        AtomicInteger callCount = new AtomicInteger(0);
        Flow<OrderRequest, String> step = Flow.step((ctx, req) -> {
            if (callCount.incrementAndGet() < 2) {
                return Outcome.failed(Failure.of("INC_ERR", "Increment error"));
            }
            return Outcome.accepted("inc:" + req.getOrderId());
        });

        Flow<OrderRequest, String> flow = FlowRetries.wrap(step, policy, Function.identity());

        FlowResult<String> result = Local.compile(flow).run(new OrderRequest("1004", 40));
        FlowAssertions.assertAccepted(result, "inc:1004");
        assertEquals(2, callCount.get());
    }

    @Test
    public void testRetryAttemptsExhausted() {
        FlowRetryPolicy<OrderRequest> policy = FlowRetries.fixed(3, 10);

        AtomicInteger callCount = new AtomicInteger(0);
        Flow<OrderRequest, String> step = Flow.step((ctx, req) -> {
            callCount.incrementAndGet();
            return Outcome.failed(Failure.of("PERSISTENT_FAILURE", "Target system is completely down"));
        });

        Flow<OrderRequest, String> flow = policy.wrap(step, Function.identity());

        FlowResult<String> result = Local.compile(flow).run(new OrderRequest("1005", 50));
        Failure failure = FlowAssertions.assertFailed(result, "PERSISTENT_FAILURE");
        assertNotNull(failure);
        assertEquals("Target system is completely down", failure.message());
        assertEquals(3, callCount.get());
    }

    @Test
    public void testConditionalRetryRetryable() {
        FlowRetryPolicy<OrderRequest> policy = FlowRetryPolicy.<OrderRequest>builder()
                .maxAttempts(3)
                .backoff(Backoffs.fixed(10))
                .retryOn(failure -> "RETRYABLE_ERROR".equals(failure.code()))
                .build();

        AtomicInteger callCount = new AtomicInteger(0);
        Flow<OrderRequest, String> step = Flow.step((ctx, req) -> {
            if (callCount.incrementAndGet() < 2) {
                return Outcome.failed(Failure.of("RETRYABLE_ERROR", "Temporary database lock timeout"));
            }
            return Outcome.accepted("recovered:" + req.getOrderId());
        });

        Flow<OrderRequest, String> flow = policy.wrap(step, Function.identity());

        FlowResult<String> result = Local.compile(flow).run(new OrderRequest("1006", 60));
        FlowAssertions.assertAccepted(result, "recovered:1006");
        assertEquals(2, callCount.get());
    }

    @Test
    public void testConditionalRetryNonRetryableFastFail() {
        FlowRetryPolicy<OrderRequest> policy = FlowRetryPolicy.<OrderRequest>builder()
                .maxAttempts(4)
                .backoff(Backoffs.fixed(10))
                .retryOnCodes("RETRYABLE_TIMEOUT", "NETWORK_ERROR")
                .build();

        AtomicInteger callCount = new AtomicInteger(0);
        Flow<OrderRequest, String> step = Flow.step((ctx, req) -> {
            callCount.incrementAndGet();
            return Outcome.failed(Failure.of("INVALID_PARAM", "Account does not exist"));
        });

        Flow<OrderRequest, String> flow = policy.wrap(step, Function.identity());

        FlowResult<String> result = Local.compile(flow).run(new OrderRequest("1007", 70));
        Reason reason = FlowAssertions.assertRejected(result, FlowRetryPolicy.DEFAULT_ABORT_CODE);
        assertNotNull(reason);
        assertTrue(reason.message().contains("INVALID_PARAM"));
        // 首次执行失败发现不可重试，第 2 次 before 立即 Gate.reject 短路退出，绝不空耗剩余 2 次重试
        assertEquals(1, callCount.get());
    }

    @Test
    public void testNamedRetryPolicyRegistryLookup() {
        NamedRetryPolicyRegistry registry = new NamedRetryPolicyRegistry();
        registry.register(new NamedRetryPolicyFactory() {
            @Override
            public String key() {
                return "order-charge-retry";
            }

            @Override
            public RetryPolicy create() {
                return RetryPolicy.builder()
                        .maxRetries(2) // maxAttempts = 3
                        .backoff(Backoffs.fixed(20))
                        .build();
            }
        });

        FlowRetryPolicy<OrderRequest> policy = FlowRetries.named(registry, "order-charge-retry");
        assertEquals(3, policy.resolveMaxAttempts());
        assertEquals(20, policy.resolveBackoff().calculateMillis(1));

        AtomicInteger callCount = new AtomicInteger(0);
        Flow<OrderRequest, String> step = Flow.step((ctx, req) -> {
            if (callCount.incrementAndGet() < 2) {
                return Outcome.failed(Failure.of("CHARGE_FAIL", "Bank timeout"));
            }
            return Outcome.accepted("charged:" + req.getOrderId());
        });

        Flow<OrderRequest, String> flow = policy.wrap(step, Function.identity());

        FlowResult<String> result = Local.compile(flow).run(new OrderRequest("1008", 80));
        FlowAssertions.assertAccepted(result, "charged:1008");
        assertEquals(2, callCount.get());
    }

    @Test
    public void testDynamicRetryPolicyRegistryLookup() {
        TestConfigContext config = TestConfigContext.create();
        try {
            DynamicRetryPolicyRegistry.setRegistry(new ConfigDrivenRegistry<>(
                    config.getConfigManager(),
                    "retry.policy.*",
                    RetryPolicyParser::create));

            config.put("retry.policy.dynamic-flow",
                    "{\"maxRetries\":3,\"backoff\":{\"type\":\"exponential\",\"params\":{\"initialDelay\":15,\"multiplier\":2.0,\"maxDelay\":100}}}");

            FlowRetryPolicy<OrderRequest> policy = FlowRetries.named("dynamic-flow");
            assertEquals(4, policy.resolveMaxAttempts());
            assertEquals(15, policy.resolveBackoff().calculateMillis(1));
            assertEquals(30, policy.resolveBackoff().calculateMillis(2));

            AtomicInteger callCount = new AtomicInteger(0);
            Flow<OrderRequest, String> step = Flow.step((ctx, req) -> {
                if (callCount.incrementAndGet() < 3) {
                    return Outcome.failed(Failure.of("DYNAMIC_ERR", "Dynamic test error"));
                }
                return Outcome.accepted("dynamic-ok:" + req.getOrderId());
            });

            Flow<OrderRequest, String> flow = policy.wrap(step, Function.identity());

            FlowResult<String> result = Local.compile(flow).run(new OrderRequest("1009", 90));
            FlowAssertions.assertAccepted(result, "dynamic-ok:1009");
            assertEquals(3, callCount.get());
        } finally {
            config.destroy();
            DynamicRetryPolicyRegistry.reset();
        }
    }

    @Test
    public void testBuilderOptionsAndCustomFactories() {
        FlowRetryPolicy<String> customPolicy = FlowRetryPolicy.<String>builder()
                .maxAttempts(2)
                .backoff(Backoffs.fixed(5))
                .abortOnCodes("FATAL_BIZ_CODE")
                .nonRetryableReasonCode("CUSTOM_ABORT")
                .reasonFactory((failure, key) -> Reason.of("CUSTOM_ABORT", "Blocked key=" + key + ", fail=" + failure.code()))
                .failureFactory((attempt, key) -> Failure.of("CUSTOM_EXHAUSTED", "Exhausted for " + key + " after " + attempt))
                .build();

        assertEquals(2, customPolicy.getMaxAttempts().intValue());
        assertEquals("CUSTOM_ABORT", customPolicy.getNonRetryableReasonCode());

        // 验证不可重试判定
        Assert.assertFalse(customPolicy.isRetryable(Failure.of("FATAL_BIZ_CODE", "Error")));
        Assert.assertTrue(customPolicy.isRetryable(Failure.of("OTHER_ERROR", "Error")));

        // 验证 direct before gate 超限 fail
        PolicyContext attempt3Ctx = createMockContext(3, null);
        Gate gateExhausted = customPolicy.before(attempt3Ctx, "key1");
        assertTrue(gateExhausted instanceof Gate.Fail);
        assertEquals("CUSTOM_EXHAUSTED", ((Gate.Fail) gateExhausted).failure().code());
    }

    @Test
    public void testCancellationInterruptsBackoff() throws Exception {
        FlowRetryPolicy<String> policy = FlowRetries.fixed(3, 5000);
        Cancellation cancellation = Cancellation.create();

        PolicyContext cancelCtx = createMockContext(2, cancellation);

        Thread targetThread = Thread.currentThread();
        cancellation.attach(targetThread);
        try {
            Thread asyncCanceller = new Thread(() -> {
                try {
                    Thread.sleep(50);
                    cancellation.cancel();
                } catch (InterruptedException ignored) {
                }
            });
            asyncCanceller.start();

            try {
                policy.before(cancelCtx, "test-cancel");
                Assert.fail("Expected CancellationException or Gate.Fail");
            } catch (CancellationException e) {
                // Expected when cancellation token is thrown
                assertTrue(cancellation.isCancelled());
            }
            asyncCanceller.join();
        } finally {
            cancellation.detach(targetThread);
        }
    }

    @Test
    public void testFlowCancellationDuringBackoff() {
        FlowRetryPolicy<String> policy = FlowRetries.fixed(3, 5000);
        Cancellation cancellation = Cancellation.create();

        Flow<String, String> step = Flow.step((ctx, in) -> Outcome.failed(Failure.of("FAIL", "fail1")));
        Flow<String, String> flow = policy.wrap(step, Function.identity());

        Thread asyncCanceller = new Thread(() -> {
            try {
                Thread.sleep(100);
                cancellation.cancel();
            } catch (InterruptedException ignored) {
            }
        });
        asyncCanceller.start();

        FlowResult<String> result = Local.compile(flow).run("input", cancellation);
        FlowAssertions.assertCancelled(result);
    }

    @Test
    public void testToRetryHelpers() {
        FlowRetryPolicy<String> policy = FlowRetries.exponential(5, 100, 2.0, 1000);
        Retry retry1 = FlowRetries.toRetry(policy);
        assertEquals(5, retry1.maxAttempts());
        assertEquals(Duration.ZERO, retry1.backoff());

        Retry retry2 = policy.toRetry(Duration.ofMillis(50));
        assertEquals(5, retry2.maxAttempts());
        assertEquals(Duration.ofMillis(50), retry2.backoff());

        Retry retry3 = FlowRetries.maxAttempts(4);
        assertEquals(4, retry3.maxAttempts());
    }
}
