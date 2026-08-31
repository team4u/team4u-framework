package com.team4u.framework.flow.retry;

import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.durable.DurableExecutable;
import com.team4u.framework.flow.durable.DurableResult;
import com.team4u.framework.flow.durable.DurableRuntime;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
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
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Flow 重试策略适配层单元测试（涵盖 Local 内存与 Durable 持久化状态机）。
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

        Flow<OrderRequest, String> flow = FlowRetries.policy(step, policy, Function.identity());

        long start = System.currentTimeMillis();
        FlowResult<String> result = Local.compile(flow).run(new OrderRequest("1002", 20));
        long duration = System.currentTimeMillis() - start;

        FlowAssertions.assertAccepted(result, "done:1002");
        assertEquals(3, callCount.get());
        // 30ms (after attempt 1) + 60ms (after attempt 2) = ~90ms
        assertTrue("Duration should reflect exponential backoff delay sum: " + duration, duration >= 70);
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
        FlowRetryPolicy<OrderRequest> policy = FlowRetries.increment(3, 40, 20);
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
        Failure failure = FlowAssertions.assertFailed(result, "INVALID_PARAM");
        assertNotNull(failure);
        // 不可重试失败直接返回，不继续重试
        assertEquals(1, callCount.get());
    }

    @Test
    public void testAbortOnCodes() {
        FlowRetryPolicy<String> policy = FlowRetryPolicy.<String>builder()
                .maxAttempts(4)
                .backoff(Backoffs.fixed(10))
                .abortOnCodes("FATAL_BIZ_CODE")
                .build();

        AtomicInteger callCount = new AtomicInteger(0);
        Flow<String, String> step = Flow.step((ctx, in) -> {
            callCount.incrementAndGet();
            return Outcome.failed(Failure.of("FATAL_BIZ_CODE", "Fatal abort"));
        });

        Flow<String, String> flow = policy.wrap(step);

        FlowResult<String> result = Local.compile(flow).run("test-abort");
        Failure failure = FlowAssertions.assertFailed(result, "FATAL_BIZ_CODE");
        assertNotNull(failure);
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
    public void testFlowCancellationDuringBackoff() {
        FlowRetryPolicy<String> policy = FlowRetries.fixed(3, 5000);
        Cancellation cancellation = Cancellation.create();

        Flow<String, String> step = Flow.step((ctx, in) -> Outcome.failed(Failure.of("FAIL", "fail1")));
        Flow<String, String> flow = policy.wrap(step);

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
    public void testDurableIntegration() {
        final AtomicInteger calls = new AtomicInteger(0);
        Operation<String, String> flaky = (context, input) -> {
            int call = calls.incrementAndGet();
            if (call < 3) {
                return Outcome.failed(Failure.of("FLAKY_" + call, "transient error"));
            }
            return Outcome.accepted(input + ">ok@" + call);
        };

        FlowRetryPolicy<String> policy = FlowRetries.fixed(3, 20);
        Flow<String, String> flow = policy.wrap(Flow.step(flaky));

        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                DurableRuntime.builder(store)
                        .stateMapper(createTestStateMapper())
                        .build()
                        .compile(flow, "durable-retry", 1);

        DurableResult<String> current = executable.start("exec-1", "data");
        int recovers = 0;
        while (current instanceof DurableResult.Active && recovers < 5) {
            DurableResult.Active<String> active = (DurableResult.Active<String>) current;
            assertTrue(active.wakeAt().isPresent());
            waitPast(active.wakeAt().get());
            current = executable.recover("exec-1");
            recovers++;
        }

        assertTrue(current instanceof DurableResult.Completed);
        assertEquals("data>ok@3", current.requireAccepted());
        assertEquals(3, calls.get());
    }

    @Test
    public void testDurableAttemptsExhausted() {
        final AtomicInteger calls = new AtomicInteger(0);
        Operation<String, String> alwaysFails = (context, input) -> {
            calls.incrementAndGet();
            return Outcome.failed(Failure.of("PERM_ERROR", "permanent failure"));
        };

        FlowRetryPolicy<String> policy = FlowRetries.fixed(2, 10);
        Flow<String, String> flow = FlowRetries.policy(Flow.step(alwaysFails), policy);

        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                DurableRuntime.builder(store)
                        .stateMapper(createTestStateMapper())
                        .build()
                        .compile(flow, "durable-retry-exhausted", 1);

        DurableResult<String> first = executable.start("exec-2", "data");
        assertTrue(first instanceof DurableResult.Active);
        DurableResult.Active<String> active = (DurableResult.Active<String>) first;
        waitPast(active.wakeAt().get());
        DurableResult<String> done = executable.recover("exec-2");

        assertTrue(done instanceof DurableResult.Completed);
        Outcome.Failed<String> failed = (Outcome.Failed<String>) ((DurableResult.Completed<String>) done).outcome();
        assertEquals("PERM_ERROR", failed.failure().code());
        assertEquals(2, calls.get());
    }

    private static com.team4u.framework.flow.durable.snapshot.StateMapper createTestStateMapper() {
        return com.team4u.framework.flow.durable.snapshot.CompositeStateMapper.withDefault(
                new com.team4u.framework.flow.durable.snapshot.SerializerStateMapper(
                        "json:jackson", 1,
                        obj -> com.team4u.framework.serializer.json.JsonUtil.toJsonStr(obj).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        bytes -> com.team4u.framework.serializer.json.JsonUtil.toBean(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), FlowRetryState.class)));
    }

    private static void waitPast(Instant wake) {
        while (Instant.now().isBefore(wake.plusMillis(5))) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
