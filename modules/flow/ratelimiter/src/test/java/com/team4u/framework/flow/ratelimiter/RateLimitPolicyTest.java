package com.team4u.framework.flow.ratelimiter;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Metadata;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.test.FlowAssertions;
import com.team4u.framework.kv.test.TestKvContext;
import com.team4u.framework.ratelimiter.api.RateLimiters;
import com.team4u.framework.ratelimiter.core.RateLimitEngine;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Flow 限流策略适配层单元测试
 *
 * @author jay.wu
 */
public class RateLimitPolicyTest {

    @After
    public void tearDown() {
        RateLimiters.destroy();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderRequest {
        private String userId;
        private int count;
    }

    private PolicyContext testContext() {
        return new PolicyContext() {
            @Override
            public Metadata metadata() {
                return null;
            }

            @Override
            public int attempt() {
                return 1;
            }

            @Override
            public Cancellation.Signal cancellation() {
                return null;
            }
        };
    }

    @Test
    public void proceedsWhenAllowed() {
        TestConfigContext config = TestConfigContext.create();
        config.put("team4u.ratelimiter.order.create",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                        + "\"windowMillis\":60000,\"threshold\":5}]");
        TestKvContext kv = TestKvContext.create();

        try {
            RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());

            RateLimitPolicy<OrderRequest> policy = RateLimitPolicy.of(
                    "order.create", OrderRequest::getUserId);

            Flow<OrderRequest, String> flow = Flow.<OrderRequest, String>step(
                    (ctx, req) -> Outcome.accepted("order:" + req.getUserId()))
                    .policy(policy, req -> req);

            FlowResult<String> result = Local.compile(flow).run(new OrderRequest("user1", 1));
            FlowAssertions.assertAccepted(result, "order:user1");
        } finally {
            RateLimiters.destroy();
            config.destroy();
            kv.close();
        }
    }

    @Test
    public void failsWhenDeniedInDefaultFailMode() {
        TestConfigContext config = TestConfigContext.create();
        config.put("team4u.ratelimiter.order.create",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                        + "\"windowMillis\":60000,\"threshold\":1}]");
        TestKvContext kv = TestKvContext.create();

        try {
            RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());

            RateLimitPolicy<OrderRequest> policy = RateLimitPolicies.fail(
                    "order.create", OrderRequest::getUserId);

            Flow<OrderRequest, String> flow = Flow.<OrderRequest, String>step(
                    (ctx, req) -> Outcome.accepted("order:" + req.getUserId()))
                    .policy(policy, req -> req);

            // 第一次成功放行
            FlowResult<String> result1 = Local.compile(flow).run(new OrderRequest("user1", 1));
            FlowAssertions.assertAccepted(result1, "order:user1");

            // 第二次限流触发 Failed
            FlowResult<String> result2 = Local.compile(flow).run(new OrderRequest("user1", 1));
            Failure failure = FlowAssertions.assertFailed(result2, RateLimitPolicy.DEFAULT_FAILURE_CODE);
            assertNotNull(failure);
            assertTrue(failure.message().contains("fw"));
        } finally {
            RateLimiters.destroy();
            config.destroy();
            kv.close();
        }
    }

    @Test
    public void rejectsWhenDeniedInRejectMode() {
        TestConfigContext config = TestConfigContext.create();
        config.put("team4u.ratelimiter.order.create",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                        + "\"windowMillis\":60000,\"threshold\":1}]");
        TestKvContext kv = TestKvContext.create();

        try {
            RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());

            RateLimitPolicy<OrderRequest> policy = RateLimitPolicies.reject(
                    "order.create", OrderRequest::getUserId);

            Flow<OrderRequest, String> flow = Flow.<OrderRequest, String>step(
                    (ctx, req) -> Outcome.accepted("order:" + req.getUserId()))
                    .policy(policy, req -> req);

            // 第一次成功
            FlowResult<String> result1 = Local.compile(flow).run(new OrderRequest("user1", 1));
            FlowAssertions.assertAccepted(result1, "order:user1");

            // 第二次限流触发 Rejected
            FlowResult<String> result2 = Local.compile(flow).run(new OrderRequest("user1", 1));
            Reason reason = FlowAssertions.assertRejected(result2, RateLimitPolicy.DEFAULT_REJECT_CODE);
            assertNotNull(reason);
            assertTrue(reason.message().contains("order.create"));
        } finally {
            RateLimiters.destroy();
            config.destroy();
            kv.close();
        }
    }

    @Test
    public void retryWithRateLimitPolicy() {
        TestConfigContext config = TestConfigContext.create();
        // 1秒令牌桶，阈值 1
        config.put("team4u.ratelimiter.retry.point",
                "[{\"id\":\"tb\",\"algorithm\":\"token-bucket\","
                        + "\"windowMillis\":1000,\"threshold\":1}]");
        TestKvContext kv = TestKvContext.create();

        try {
            RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());

            AtomicInteger executeCount = new AtomicInteger(0);

            // PersistentPolicy 在外，Policy 在内：每次重试重新获取令牌
            Flow<String, String> flow = Flow.<String, String>step(
                    (ctx, in) -> {
                        executeCount.incrementAndGet();
                        return Outcome.accepted("done:" + in);
                    })
                    .policy(RateLimitPolicy.fail("retry.point"), in -> in)
                    .persistentPolicy(retryStub(3, Duration.ofMillis(10)), in -> in);

            // 第一次执行成功
            FlowResult<String> result1 = Local.compile(flow).run("key1");
            FlowAssertions.assertAccepted(result1, "done:key1");
            assertEquals(1, executeCount.get());

            // 第二次执行：首次和重试均受限（未推进虚拟时钟），最终 Failed
            FlowResult<String> result2 = Local.compile(flow).run("key1");
            FlowAssertions.assertFailed(result2, RateLimitPolicy.DEFAULT_FAILURE_CODE);
        } finally {
            RateLimiters.destroy();
            config.destroy();
            kv.close();
        }
    }

    @Test
    public void permitsExtractorSupport() {
        TestConfigContext config = TestConfigContext.create();
        config.put("team4u.ratelimiter.batch.point",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                        + "\"windowMillis\":60000,\"threshold\":10,\"key\":\"${userId}\"}]");
        TestKvContext kv = TestKvContext.create();

        try {
            RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());

            RateLimitPolicy<OrderRequest> policy = RateLimitPolicy.<OrderRequest>builder()
                    .point("batch.point")
                    .contextExtractor(req -> req)
                    .permitsExtractor(OrderRequest::getCount)
                    .build();

            Flow<OrderRequest, String> flow = Flow.<OrderRequest, String>step(
                    (ctx, req) -> Outcome.accepted("count=" + req.getCount()))
                    .policy(policy, req -> req);

            // 消耗 6 个 permits -> 成功 (剩余 4)
            FlowResult<String> r1 = Local.compile(flow).run(new OrderRequest("u1", 6));
            FlowAssertions.assertAccepted(r1, "count=6");

            // 消耗 5 个 permits -> 超限 (需要 5 > 剩余 4)
            FlowResult<String> r2 = Local.compile(flow).run(new OrderRequest("u1", 5));
            FlowAssertions.assertFailed(r2, RateLimitPolicy.DEFAULT_FAILURE_CODE);

            // 消耗 4 个 permits (另一个用户 u2) -> 成功
            FlowResult<String> r3 = Local.compile(flow).run(new OrderRequest("u2", 4));
            FlowAssertions.assertAccepted(r3, "count=4");
        } finally {
            RateLimiters.destroy();
            config.destroy();
            kv.close();
        }
    }

    @Test
    public void explicitEngineInstance() {
        TestConfigContext config = TestConfigContext.create();
        config.put("team4u.ratelimiter.custom.engine.point",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                        + "\"windowMillis\":60000,\"threshold\":1}]");
        TestKvContext kv = TestKvContext.create();

        try {
            RateLimitEngine engine = new RateLimitEngine(config.getConfigManager(), kv.store(), kv.clock());

            RateLimitPolicy<String> policy = RateLimitPolicies.of(engine, "custom.engine.point");

            Gate gate1 = policy.before(testContext(), "userA");
            assertTrue(gate1 instanceof Gate.Proceed);

            Gate gate2 = policy.before(testContext(), "userA");
            assertTrue(gate2 instanceof Gate.Fail);

            engine.destroy();
        } finally {
            config.destroy();
            kv.close();
        }
    }

    @Test
    public void customFailureAndReasonFactories() {
        TestConfigContext config = TestConfigContext.create();
        config.put("team4u.ratelimiter.factory.point",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                        + "\"windowMillis\":60000,\"threshold\":1}]");
        TestKvContext kv = TestKvContext.create();

        try {
            RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());

            // 1. Custom Failure Factory
            RateLimitPolicy<String> failPolicy = RateLimitPolicy.<String>builder()
                    .point("factory.point")
                    .action(RateLimitAction.FAIL)
                    .failureFactory((result, key) -> Failure.of("MY_CUSTOM_LIMIT", "Custom limit for " + key))
                    .build();

            policyAcquireTwice(failPolicy);
            Gate failGate = failPolicy.before(testContext(), "k1");
            assertTrue(failGate instanceof Gate.Fail);
            assertEquals("MY_CUSTOM_LIMIT", ((Gate.Fail) failGate).failure().code());
            assertEquals("Custom limit for k1", ((Gate.Fail) failGate).failure().message());

            RateLimiters.destroy();
            RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());

            // 2. Custom Reason Factory
            RateLimitPolicy<String> rejectPolicy = RateLimitPolicy.<String>builder()
                    .point("factory.point")
                    .action(RateLimitAction.REJECT)
                    .reasonFactory((result, key) -> Reason.of("MY_CUSTOM_REJECT", "Custom reject for " + key))
                    .build();

            policyAcquireTwice(rejectPolicy);
            Gate rejectGate = rejectPolicy.before(testContext(), "k1");
            assertTrue(rejectGate instanceof Gate.Reject);
            assertEquals("MY_CUSTOM_REJECT", ((Gate.Reject) rejectGate).reason().code());
            assertEquals("Custom reject for k1", ((Gate.Reject) rejectGate).reason().message());
        } finally {
            RateLimiters.destroy();
            config.destroy();
            kv.close();
        }
    }

    private void policyAcquireTwice(RateLimitPolicy<String> policy) {
        policy.before(testContext(), "k1");
    }

    private static PersistentPolicy<String, Integer> retryStub(final int maxAttempts, final Duration backoff) {
        return new PersistentPolicy<String, Integer>() {
            @Override public Integer initialState(String key) { return 1; }
            @Override public Before<Integer> before(PolicyContext ctx, String key, Integer state) {
                return PersistentPolicy.proceed(state);
            }
            @Override public After<Integer> after(PolicyContext ctx, String key, Integer state, com.team4u.framework.flow.model.Completion completion) {
                if (completion != null && completion.kind() == Outcome.Kind.FAILED && state < maxAttempts) {
                    return PersistentPolicy.retryAt(java.time.Instant.now().plus(backoff), state + 1);
                }
                return PersistentPolicy.returning(state);
            }
        };
    }
}
