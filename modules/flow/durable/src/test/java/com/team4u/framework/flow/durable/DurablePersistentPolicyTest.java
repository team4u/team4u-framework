package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static com.team4u.framework.flow.durable.DurableTestOps.RecordingOp;
import static com.team4u.framework.flow.durable.DurableTestOps.SimulatedCrash;
import static com.team4u.framework.flow.durable.DurableTestOps.acceptedValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

/** 组5：PersistentPolicy 跨恢复 — key/state/attempt 持久化、绝对 wake、RetryAt 重试。 */
public class DurablePersistentPolicyTest {

    /** 计数型策略：首次 WaitUntil(绝对时间, state=n)，之后 Proceed(state=done)。 */
    static final class WaitingPolicy implements PersistentPolicy<String, Integer> {
        final AtomicInteger beforeCalls = new AtomicInteger();
        final AtomicInteger initialStateCalls = new AtomicInteger();
        volatile Instant waitUntil;
        volatile Integer waitedState;

        @Override
        public Integer initialState(String key) {
            initialStateCalls.incrementAndGet();
            return 0;
        }

        @Override
        public Before<Integer> before(PolicyContext context, String key, Integer state) {
            beforeCalls.incrementAndGet();
            if (state == 0) {
                waitUntil = Instant.now().plusMillis(50);
                return PersistentPolicy.waitUntil(waitUntil, 1);
            }
            return PersistentPolicy.proceed(99);
        }

        @Override
        public After<Integer> after(PolicyContext context, String key, Integer state,
                                    Completion completion) {
            return PersistentPolicy.returning(state == null ? -1 : state);
        }
    }

    private static DurableExecutable<String, String> compile(Flow<String, String> flow,
                                                             DurableStore store) {
        return Durable.builder(store).build().compile(flow, "pp", 1);
    }

    @Test
    public void waitUntilParksWithAbsoluteWakeThenProceedsAfterRecover() {
        WaitingPolicy policy = new WaitingPolicy();
        RecordingOp body = new RecordingOp("body");
        Flow<String, String> flow = Flow.<String, String>step(body)
                .persistentPolicy(policy, input -> "KEY");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compile(flow, store);
        DurableResult<String> first = executable.start("e", "in");
        // 首驱动：WaitUntil → ACTIVE + wake（不占线程）
        assertTrue(first.getClass().getSimpleName(),
                first instanceof DurableResult.Active);
        DurableResult.Active<String> active = (DurableResult.Active<String>) first;
        assertTrue(active.wakeAt().isPresent());
        assertNotNull(policy.waitUntil);
        // body 尚未执行
        assertEquals(0, body.calls());
        // 等待 wake 过去后 recover： Proceed → body → 完成
        while (Instant.now().isBefore(policy.waitUntil.plusMillis(5))) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        DurableResult<String> done = executable.recover("e");
        assertTrue(done.getClass().getSimpleName(), done instanceof DurableResult.Completed);
        assertEquals("in>body", acceptedValue(done));
        assertEquals(1, body.calls());
    }

    @Test
    public void stateAndAttemptSurviveRecovery() {
        // before 在恢复后看到持久化的 state（=1），不再走 WaitUntil
        WaitingPolicy policy = new WaitingPolicy();
        RecordingOp body = new RecordingOp("body");
        Flow<String, String> flow = Flow.<String, String>step(body)
                .persistentPolicy(policy, input -> "KEY");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compile(flow, store);
        DurableResult<String> first = executable.start("e", "in");
        assertTrue(first instanceof DurableResult.Active);
        // 快照包含 policy:<path> 槽（state=1）
        DurableSnapshot snapshot = store.load("e").get();
        boolean hasPolicySlot = false;
        for (String role : snapshot.slots().keySet()) {
            if (role.startsWith("policy:")) {
                hasPolicySlot = true;
            }
        }
        assertTrue("快照必须包含 policy state 槽", hasPolicySlot);
        // 不等待直接 recover：仍在 wake 前 → 依旧 Active
        DurableResult<String> stillWaiting = executable.recover("e");
        assertTrue(stillWaiting instanceof DurableResult.Active);
        // 等待结束后 recover：state=1 → Proceed(99) → body
        while (Instant.now().isBefore(policy.waitUntil.plusMillis(5))) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        DurableResult<String> done = executable.recover("e");
        assertTrue(done.getClass().getSimpleName(), done instanceof DurableResult.Completed);
        assertEquals(2, policy.beforeCalls.get());
    }

    @Test
    public void keyIsPersistedAcrossRecovery() {
        // initialState 只应被调用一次（key 之后跨恢复保留）
        final AtomicInteger stateInits = new AtomicInteger();
        PersistentPolicy<String, String> policy = new PersistentPolicy<String, String>() {
            @Override
            public String initialState(String key) {
                stateInits.incrementAndGet();
                return "fresh:" + key;
            }

            @Override
            public Before<String> before(PolicyContext context, String key, String state) {
                return PersistentPolicy.proceed("seen:" + key + "/" + state);
            }

            @Override
            public After<String> after(PolicyContext context, String key, String state,
                                       Completion completion) {
                return PersistentPolicy.returning(state);
            }
        };
        RecordingOp body = new RecordingOp("body").crashOnCall(1);
        Flow<String, String> flow = Flow.<String, String>step(body)
                .persistentPolicy(policy, input -> "K1");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compile(flow, store);
        try {
            executable.start("e", "in");
        } catch (SimulatedCrash expected) {
            // body 首次执行崩溃（policy Proceed 已提交）
        }
        // 崩溃时快照已包含 key:<control path> 与 policy:<control path> 槽
        DurableSnapshot crashed = store.load("e").get();
        assertTrue(crashed.slots().keySet().toString(),
                crashed.slots().containsKey("key:$"));
        assertTrue(crashed.slots().keySet().toString(),
                crashed.slots().containsKey("policy:$"));
        executable.recover("e");
        assertEquals("initialState 只能调用一次", 1, stateInits.get());
    }

    @Test
    public void retryAtAfterCompletionRetriesBody() {
        // after 返回 RetryAt(绝对时间)：落 ACTIVE+wake；恢复后重跑 body
        final AtomicInteger bodyCalls = new AtomicInteger();
        final Instant[] retryAt = new Instant[1];
        PersistentPolicy<String, Integer> policy = new PersistentPolicy<String, Integer>() {
            @Override
            public Integer initialState(String key) {
                return 0;
            }

            @Override
            public Before<Integer> before(PolicyContext context, String key, Integer state) {
                return PersistentPolicy.proceed(state);
            }

            @Override
            public After<Integer> after(PolicyContext context, String key, Integer state,
                                        Completion completion) {
                if (state == 0) {
                    retryAt[0] = Instant.now().plusMillis(40);
                    return PersistentPolicy.retryAt(retryAt[0], 1);
                }
                return PersistentPolicy.returning(state);
            }
        };
        Operation<String, String> body = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                bodyCalls.incrementAndGet();
                return Outcome.accepted(input + "-b" + bodyCalls.get());
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(body)
                .persistentPolicy(policy, input -> "K");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compile(flow, store);
        DurableResult<String> first = executable.start("e", "in");
        assertTrue(first instanceof DurableResult.Active);
        assertEquals(1, bodyCalls.get());
        while (Instant.now().isBefore(retryAt[0].plusMillis(5))) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        DurableResult<String> done = executable.recover("e");
        assertTrue(done.getClass().getSimpleName(), done instanceof DurableResult.Completed);
        assertEquals(2, bodyCalls.get());
        assertEquals("in-b2", acceptedValue(done));
    }

    @Test
    public void rejectDecisionCompletesWithRejected() {
        PersistentPolicy<String, String> policy = new PersistentPolicy<String, String>() {
            @Override
            public String initialState(String key) {
                return "s";
            }

            @Override
            public Before<String> before(PolicyContext context, String key, String state) {
                return PersistentPolicy.reject(Reason.of("DENIED", "no"), state);
            }

            @Override
            public After<String> after(PolicyContext context, String key, String state,
                                       Completion completion) {
                return PersistentPolicy.returning(state);
            }
        };
        RecordingOp body = new RecordingOp("body");
        Flow<String, String> flow = Flow.<String, String>step(body)
                .persistentPolicy(policy, input -> "K");
        DurableResult<String> result = compile(flow, new InMemoryDurableStore())
                .start("e", "in");
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals(Outcome.Kind.REJECTED,
                ((DurableResult.Completed<String>) result).outcome().kind());
        assertEquals(0, body.calls());
    }

    @Test
    public void failDecisionCompletesWithFailed() {
        PersistentPolicy<String, String> policy = new PersistentPolicy<String, String>() {
            @Override
            public String initialState(String key) {
                return "s";
            }

            @Override
            public Before<String> before(PolicyContext context, String key, String state) {
                return PersistentPolicy.fail(Failure.of("POLICY_FAIL", "no"), state);
            }

            @Override
            public After<String> after(PolicyContext context, String key, String state,
                                       Completion completion) {
                return PersistentPolicy.returning(state);
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(new RecordingOp("body"))
                .persistentPolicy(policy, input -> "K");
        DurableResult<String> result = compile(flow, new InMemoryDurableStore())
                .start("e", "in");
        DurableResult.Completed<String> completed = (DurableResult.Completed<String>) result;
        assertEquals(Outcome.Kind.FAILED, completed.outcome().kind());
        assertEquals("POLICY_FAIL", ((Outcome.Failed<String>) completed.outcome())
                .failure().code());
    }

    @Test
    public void policyExceptionBecomesStableFailedOutcome() {
        PersistentPolicy<String, String> policy = new PersistentPolicy<String, String>() {
            @Override
            public String initialState(String key) {
                return "s";
            }

            @Override
            public Before<String> before(PolicyContext context, String key, String state) {
                throw new IllegalStateException("policy blew up");
            }

            @Override
            public After<String> after(PolicyContext context, String key, String state,
                                       Completion completion) {
                return PersistentPolicy.returning(state);
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(new RecordingOp("body"))
                .persistentPolicy(policy, input -> "K");
        DurableResult<String> result = compile(flow, new InMemoryDurableStore())
                .start("e", "in");
        Outcome.Failed<String> failed =
                (Outcome.Failed<String>) ((DurableResult.Completed<String>) result).outcome();
        assertEquals("POLICY_EXCEPTION", failed.failure().code());
        assertTrue(failed.failure().message().contains("IllegalStateException"));
        assertTrue(failed.failure().message().contains("policy blew up"));
    }

    @Test
    public void attemptCounterIncrementsAcrossRetryAt() {
        final java.util.List<Integer> attempts = new java.util.ArrayList<Integer>();
        final Instant[] retryAt = new Instant[1];
        PersistentPolicy<String, Integer> policy = new PersistentPolicy<String, Integer>() {
            @Override
            public Integer initialState(String key) {
                return 0;
            }

            @Override
            public Before<Integer> before(PolicyContext context, String key, Integer state) {
                attempts.add(context.attempt());
                return PersistentPolicy.proceed(state);
            }

            @Override
            public After<Integer> after(PolicyContext context, String key, Integer state,
                                        Completion completion) {
                if (state == 0) {
                    retryAt[0] = Instant.now().plusMillis(30);
                    return PersistentPolicy.retryAt(retryAt[0], 1);
                }
                return PersistentPolicy.returning(state);
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(new RecordingOp("body"))
                .persistentPolicy(policy, input -> "K");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compile(flow, store);
        executable.start("e", "in");
        while (Instant.now().isBefore(retryAt[0].plusMillis(5))) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        executable.recover("e");
        assertEquals(2, attempts.size());
        assertEquals(Integer.valueOf(1), attempts.get(0));
        assertEquals("attempt 必须跨恢复递增", Integer.valueOf(2), attempts.get(1));
    }

    @Test
    public void persistentPolicyThrowingFlowExecutionExceptionPreservesFailureCode() {
        PersistentPolicy<String, Integer> policy = new PersistentPolicy<String, Integer>() {
            @Override
            public Integer initialState(String key) {
                return 0;
            }

            @Override
            public Before<Integer> before(PolicyContext context, String key, Integer state) {
                throw new com.team4u.framework.flow.model.FlowExecutionException(
                        "DURABLE_POLICY_FAIL", "Custom durable policy failed");
            }

            @Override
            public After<Integer> after(PolicyContext context, String key, Integer state, Completion completion) {
                return PersistentPolicy.returning(state);
            }
        };

        Flow<String, String> flow = Flow.<String, String>step(new RecordingOp("body"))
                .persistentPolicy(policy, input -> "K");
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = compile(flow, store);
        DurableResult<String> result = executable.start("p-fail", "in");
        assertTrue(result instanceof DurableResult.Completed<?>);
        Outcome<String> outcome = ((DurableResult.Completed<String>) result).outcome();
        assertTrue(outcome instanceof Outcome.Failed<?>);
        Failure failure = ((Outcome.Failed<?>) outcome).failure();
        assertEquals("DURABLE_POLICY_FAIL", failure.code());
        assertEquals("Custom durable policy failed", failure.message());
    }
}
