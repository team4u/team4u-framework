package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.team4u.framework.flow.durable.DurableTestOps.acceptedValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

/** 组7：timeout/retry/Policy — 到期转 TIMEOUT、未到期通过、RETRY 上限与 backoff、Policy 顺序与异常。 */
public class DurableControlTest {

    private static DurableExecutable<String, String> compile(Flow<String, String> flow,
                                                             DurableStore store,
                                                             ExecutorService executor) {
        DurableRuntime.Builder builder = DurableRuntime.builder(store);
        if (executor != null) {
            builder.executor(executor);
        }
        return builder.build().compile(flow, "ctl", 1);
    }

    private static Outcome<String> outcome(DurableResult<String> result) {
        return ((DurableResult.Completed<String>) result).outcome();
    }

    // ------------------------------------------------------------------
    // TIMEOUT
    // ------------------------------------------------------------------

    @Test
    public void timeoutExpiryConvertsBodyToTimeoutFailure() {
        // body 睡 100ms，TIMEOUT 30ms：worker 强制截止 → 稳定 Failed(TIMEOUT)
        Operation<String, String> slow = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Outcome.accepted(input + ">slow");
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(slow)
                .timeout(Duration.ofMillis(30));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DurableResult<String> result = compile(flow, new InMemoryDurableStore(), executor)
                    .start("e", "in");
            Outcome.Failed<String> failed = (Outcome.Failed<String>) outcome(result);
            assertEquals(Outcome.Kind.FAILED, failed.kind());
            assertEquals("TIMEOUT", failed.failure().code());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void timeoutNotExpiredLetsBodyPass() {
        Operation<String, String> fast = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input + ">fast");
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(fast)
                .timeout(Duration.ofSeconds(30));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DurableResult<String> result = compile(flow, new InMemoryDurableStore(), executor)
                    .start("e", "in");
            assertEquals("in>fast", acceptedValue(result));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void timeoutWithoutExecutorFailsFastAtCompile() {
        // 行为变更（fail-fast 优先）：定义含 TIMEOUT 而未配置 executor 时，
        // compile 立即抛 INVALID_CONFIGURATION（旧语义为同步降级执行）。
        final AtomicInteger calls = new AtomicInteger();
        Operation<String, String> fast = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                calls.incrementAndGet();
                return Outcome.accepted(input + ">sync");
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(fast)
                .timeout(Duration.ofSeconds(30));
        // 不配置 executor：编译期快速失败
        try {
            compile(flow, new InMemoryDurableStore(), null);
            fail("含 TIMEOUT 而无 executor 必须 INVALID_CONFIGURATION");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.INVALID_CONFIGURATION, error.error());
        }
        assertEquals(0, calls.get());
    }

    @Test
    public void timeoutWithExecutorRunsBodyWithDeadlineEnforcement() {
        // 配置 executor 后正常：同步快路径（未到期）下 body 直接执行。
        final AtomicInteger calls = new AtomicInteger();
        Operation<String, String> fast = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                calls.incrementAndGet();
                return Outcome.accepted(input + ">sync");
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(fast)
                .timeout(Duration.ofSeconds(30));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DurableResult<String> result = compile(flow, new InMemoryDurableStore(), executor)
                    .start("e", "in");
            assertEquals("in>sync", acceptedValue(result));
            assertEquals(1, calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // RETRY (via PersistentPolicy)
    // ------------------------------------------------------------------

    private static PersistentPolicy<String, Integer> retryPolicy(final int maxAttempts, final Duration backoff) {
        return new PersistentPolicy<String, Integer>() {
            @Override public Integer initialState(String key) { return 1; }
            @Override public Before<Integer> before(PolicyContext context, String key, Integer state) {
                return PersistentPolicy.proceed(state);
            }
            @Override public After<Integer> after(PolicyContext context, String key, Integer state, Completion completion) {
                if (completion != null && completion.kind() == Outcome.Kind.FAILED && state < maxAttempts) {
                    return PersistentPolicy.retryAt(java.time.Instant.now().plus(backoff), state + 1);
                }
                return PersistentPolicy.returning(state);
            }
        };
    }

    @Test
    public void retrySucceedsAfterTransientFailures() {
        final AtomicInteger calls = new AtomicInteger();
        Operation<String, String> flaky = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                int call = calls.incrementAndGet();
                if (call < 3) {
                    return Outcome.failed(Failure.of("FLAKY_" + call, "transient"));
                }
                return Outcome.accepted(input + ">ok@" + call);
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(flaky)
                .persistentPolicy(retryPolicy(3, Duration.ofMillis(10)), s -> s);
        DurableExecutable<String, String> executable =
                compile(flow, new InMemoryDurableStore(), null);
        DurableResult<String> current = executable.start("e", "in");
        // 每次 backoff 等待都落 ACTIVE+wake：循环 recover 直到完成（最多 3 次）
        int drives = 0;
        while (current instanceof DurableResult.Active && drives < 5) {
            waitPast(((DurableResult.Active<String>) current).wakeAt().get());
            current = executable.recover("e");
            drives++;
        }
        assertTrue(current.getClass().getSimpleName(), current instanceof DurableResult.Completed);
        assertEquals("in>ok@3", acceptedValue(current));
        assertEquals(3, calls.get());
    }

    @Test
    public void retryExhaustsAttemptsAndPropagatesLastFailure() {
        final AtomicInteger calls = new AtomicInteger();
        Operation<String, String> alwaysFails = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.failed(Failure.of("ALWAYS_" + calls.incrementAndGet(), "no"));
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(alwaysFails)
                .persistentPolicy(retryPolicy(2, Duration.ofMillis(5)), s -> s);
        DurableExecutable<String, String> executable =
                compile(flow, new InMemoryDurableStore(), null);
        DurableResult<String> first = executable.start("e", "in");
        assertTrue(first instanceof DurableResult.Active);
        DurableResult.Active<String> active = (DurableResult.Active<String>) first;
        waitPast(active.wakeAt().get());
        DurableResult<String> done = executable.recover("e");
        assertTrue(done.getClass().getSimpleName(), done instanceof DurableResult.Completed);
        Outcome.Failed<String> failed = (Outcome.Failed<String>) outcome(done);
        assertEquals("ALWAYS_2", failed.failure().code());
        assertEquals("maxAttempts=2 含首次，共调用 2 次", 2, calls.get());
    }

    @Test
    public void retryBackoffIsRespectedAcrossRecovery() {
        // 第二次尝试仍失败：再次 ACTIVE+wake；attempt 递增
        final List<Integer> attempts = new ArrayList<Integer>();
        final AtomicInteger calls = new AtomicInteger();
        final java.time.Instant[] secondWake = new java.time.Instant[1];
        Operation<String, String> flaky = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                calls.incrementAndGet();
                attempts.add(context.metadata() == null ? -1 : 0);
                return Outcome.failed(Failure.of("STILL_BAD", "no"));
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(flaky)
                .persistentPolicy(retryPolicy(2, Duration.ofMillis(20)), s -> s);
        DurableExecutable<String, String> executable =
                compile(flow, new InMemoryDurableStore(), null);
        DurableResult<String> first = executable.start("e", "in");
        assertTrue(first instanceof DurableResult.Active);
        waitPast(((DurableResult.Active<String>) first).wakeAt().get());
        DurableResult<String> second = executable.recover("e");
        // 第二次尝试后 attempt=2=maxAttempts：不再重试，直接终态
        assertTrue(second.getClass().getSimpleName(), second instanceof DurableResult.Completed);
        assertEquals(Outcome.Kind.FAILED, outcome(second).kind());
    }

    // ------------------------------------------------------------------
    // Policy
    // ------------------------------------------------------------------

    /** 记录 before/after 顺序与 attempt 的策略。 */
    static final class RecordingPolicy implements Policy<String> {
        final List<String> events = new ArrayList<String>();
        final Gate gate;

        RecordingPolicy(Gate gate) {
            this.gate = gate;
        }

        @Override
        public Gate before(PolicyContext context, String key) {
            events.add("before:" + key + ":" + context.attempt());
            return gate;
        }

        @Override
        public void after(PolicyContext context, String key, Completion completion) {
            events.add("after:" + key + ":" + context.attempt() + ":" + completion.kind());
        }
    }

    @Test
    public void policyProceedRunsBeforeThenBodyThenAfter() {
        RecordingPolicy policy = new RecordingPolicy(Gate.proceed());
        Operation<String, String> body = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input + ">body");
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(body)
                .policy(policy, input -> "K");
        DurableResult<String> result = compile(flow, new InMemoryDurableStore(), null)
                .start("e", "in");
        assertEquals("in>body", acceptedValue(result));
        assertEquals(java.util.Arrays.asList(
                "before:K:1", "after:K:1:ACCEPTED"), policy.events);
    }

    @Test
    public void policyRejectSkipsBodyAndAfterSeesRejected() {
        RecordingPolicy policy = new RecordingPolicy(
                Gate.reject(Reason.of("NOPE", "no")));
        final AtomicInteger bodyCalls = new AtomicInteger();
        Operation<String, String> body = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                bodyCalls.incrementAndGet();
                return Outcome.accepted(input);
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(body)
                .policy(policy, input -> "K");
        DurableResult<String> result = compile(flow, new InMemoryDurableStore(), null)
                .start("e", "in");
        assertEquals(Outcome.Kind.REJECTED, outcome(result).kind());
        assertEquals(0, bodyCalls.get());
        assertEquals(java.util.Arrays.asList("before:K:1"), policy.events);
    }

    @Test
    public void policyFailProducesFailedOutcome() {
        RecordingPolicy policy = new RecordingPolicy(
                Gate.fail(Failure.of("GATE_DOWN", "no")));
        Operation<String, String> body = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input);
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(body)
                .policy(policy, input -> "K");
        DurableResult<String> result = compile(flow, new InMemoryDurableStore(), null)
                .start("e", "in");
        Outcome.Failed<String> failed = (Outcome.Failed<String>) outcome(result);
        assertEquals("GATE_DOWN", failed.failure().code());
    }

    @Test
    public void policyExceptionBecomesStableFailedOutcome() {
        Policy<String> broken = new Policy<String>() {
            @Override
            public Gate before(PolicyContext context, String key) {
                throw new IllegalStateException("policy before broke");
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(
                new Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext ctx, String input) {
                        return Outcome.accepted(input);
                    }
                }).policy(broken, input -> "K");
        DurableResult<String> result = compile(flow, new InMemoryDurableStore(), null)
                .start("e", "in");
        Outcome.Failed<String> failed = (Outcome.Failed<String>) outcome(result);
        assertEquals("POLICY_EXCEPTION", failed.failure().code());
        assertTrue(failed.failure().message().contains("policy before broke"));
    }

    @Test
    public void policyAfterExceptionBecomesStableFailedOutcome() {
        Policy<String> broken = new Policy<String>() {
            @Override
            public Gate before(PolicyContext context, String key) {
                return Gate.proceed();
            }

            @Override
            public void after(PolicyContext context, String key, Completion completion) {
                throw new IllegalStateException("policy after broke");
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(
                new Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext ctx, String input) {
                        return Outcome.accepted(input);
                    }
                }).policy(broken, input -> "K");
        DurableResult<String> result = compile(flow, new InMemoryDurableStore(), null)
                .start("e", "in");
        Outcome.Failed<String> failed = (Outcome.Failed<String>) outcome(result);
        assertEquals("POLICY_EXCEPTION", failed.failure().code());
        assertTrue(failed.failure().message().contains("policy after broke"));
    }

    @Test
    public void policyAttemptIsPerControlFrame() {
        // 与 Core SerialMachine 语义一致：PolicyContext.attempt() 读取的是 Policy 帧
        // 自身的 attempt（仅 PersistentPolicy 的 RetryAt 会递增它），外层 Retry
        // 递增的是 Retry 帧的 attempt，两个控制帧各自独立。
        final List<Integer> beforeAttempts = new ArrayList<Integer>();
        Policy<String> policy = new Policy<String>() {
            @Override
            public Gate before(PolicyContext context, String key) {
                beforeAttempts.add(context.attempt());
                return Gate.proceed();
            }
        };
        Operation<String, String> flaky = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.failed(Failure.of("X", "x"));
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(flaky)
                .policy(policy, input -> "K")
                .persistentPolicy(retryPolicy(2, Duration.ofMillis(5)), s -> s);
        DurableExecutable<String, String> executable =
                compile(flow, new InMemoryDurableStore(), null);
        DurableResult<String> first = executable.start("e", "in");
        assertTrue(first instanceof DurableResult.Active);
        waitPast(((DurableResult.Active<String>) first).wakeAt().get());
        executable.recover("e");
        // 每次重进入 Policy 帧都重新求值 before：两次 attempt 均为 Policy 帧初始值 1
        assertEquals(2, beforeAttempts.size());
        assertEquals(java.util.Arrays.asList(Integer.valueOf(1), Integer.valueOf(1)),
                beforeAttempts);
    }

    private static void waitPast(java.time.Instant wake) {
        while (java.time.Instant.now().isBefore(wake.plusMillis(5))) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
