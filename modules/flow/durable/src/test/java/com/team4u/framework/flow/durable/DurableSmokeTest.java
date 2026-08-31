package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;

public class DurableSmokeTest {

    static Operation<String, String> append(final String suffix) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input + suffix);
            }
        };
    }

    @Test
    public void sequenceStartAndRecover() {
        Flow<String, String> flow = Flow.<String, String>step(append("-a"))
                .then(append("-b"))
                .then(append("-c"));
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = DurableRuntime.builder(store)
                .build()
                .compile(flow, "smoke", 1);
        DurableResult<String> result = executable.start("e1", "x");
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals("x-a-b-c", result.requireAccepted());
        assertEquals(DurableLifecycle.COMPLETED, store.load("e1").get().lifecycle());
    }

    @Test
    public void failureOutcome() {
        Flow<String, String> flow = Flow.<String, String>step(new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.failed(Failure.of("BOOM", "no"));
            }
        });
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = DurableRuntime.builder(store)
                .build()
                .compile(flow, "f", 1);
        DurableResult<String> result = executable.start("e2", "x");
        assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Completed);
        Outcome<String> outcome = ((DurableResult.Completed<String>) result).outcome();
        assertEquals("非成功路径必须落定 FAILED 四态", Outcome.Kind.FAILED, outcome.kind());
        assertEquals("BOOM", ((Outcome.Failed<String>) outcome).failure().code());
    }

    @Test
    public void retryRecoversAcrossDrives() {
        final AtomicInteger attempts = new AtomicInteger();
        Operation<String, String> flaky = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (attempts.incrementAndGet() < 3) {
                    return Outcome.failed(Failure.of("FLAKY", "try again"));
                }
                return Outcome.accepted(input + ":ok");
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(flaky)
                .persistentPolicy(new com.team4u.framework.flow.api.PersistentPolicy<String, Integer>() {
                    @Override public Integer initialState(String key) { return 1; }
                    @Override public Before<Integer> before(com.team4u.framework.flow.api.PolicyContext ctx, String key, Integer state) {
                        return PersistentPolicy.proceed(state);
                    }
                    @Override public After<Integer> after(com.team4u.framework.flow.api.PolicyContext ctx, String key, Integer state, com.team4u.framework.flow.model.Completion completion) {
                        if (completion != null && completion.kind() == com.team4u.framework.flow.model.Outcome.Kind.FAILED && state < 3) {
                            return PersistentPolicy.retryAt(java.time.Instant.now(), state + 1);
                        }
                        return PersistentPolicy.returning(state);
                    }
                }, s -> s);
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = DurableRuntime.builder(store)
                .build()
                .compile(flow, "r", 1);
        DurableResult<String> result = executable.start("e3", "x");
        // 零退避 backoff：重试立即到期，驱动要么直接完成，要么落 ACTIVE+wake 等待外部再驱动
        if (result instanceof DurableResult.Completed) {
            assertEquals("x:ok", result.requireAccepted());
        } else {
            assertTrue("退避中必须是 ACTIVE，实际: " + result.getClass().getSimpleName(),
                    result instanceof DurableResult.Active);
            assertTrue("退避中必须携带 wakeAt",
                    ((DurableResult.Active<String>) result).wakeAt().isPresent());
        }
    }
}
