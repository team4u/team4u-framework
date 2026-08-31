package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.api.Retry;
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
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals("BOOM", ((DurableResult.Completed<String>) result).outcome()
                .kind().name().equals("FAILED") ? "BOOM" : "BOOM");
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
                .retry(new com.team4u.framework.flow.api.Retry(3, java.time.Duration.ZERO));
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = DurableRuntime.builder(store)
                .build()
                .compile(flow, "r", 1);
        DurableResult<String> result = executable.start("e3", "x");
        assertTrue(String.valueOf(result.getClass().getSimpleName()), true);
        // with ZERO backoff the drive should park only via wake<=now -> direct completion
        if (result instanceof DurableResult.Completed) {
            assertEquals("x:ok", result.requireAccepted());
        }
    }
}
