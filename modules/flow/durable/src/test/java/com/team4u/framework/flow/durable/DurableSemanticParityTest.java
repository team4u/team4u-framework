package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.Flows;
import com.team4u.framework.flow.Step;
import com.team4u.framework.flow.StepInterceptor;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local and Durable must preserve the same business-result and interceptor semantics.
 */
public class DurableSemanticParityTest {

    @Test
    public void nullStopReasonsFailInBothLocalAndDurableExecution() {
        Flow<String, String> guardFlow = Flows.<String>begin("null-guard-reason")
                .guard("guard", in -> false, in -> null)
                .build();

        FlowResult<String> localGuard = guardFlow.run("input").result();
        Assert.assertTrue(localGuard.isFailed());
        Assert.assertEquals("guard", localGuard.failure().nodeId());

        DurableResult<String> durableGuard = register(guardFlow).start("guard-execution", "input");
        Assert.assertTrue(durableGuard.isFailed());
        Assert.assertEquals("guard", durableGuard.failure().nodeId());
        Assert.assertEquals(IllegalStateException.class.getName(), durableGuard.failure().errorType());

        Flow<String, String> chooseFlow = Flows.<String>begin("null-choose-reason")
                .choose("route", in -> in)
                    .when("known", Flows.step("known-step", in -> in))
                    .otherwiseStop(in -> null)
                .end()
                .build();

        FlowResult<String> localChoose = chooseFlow.run("unknown").result();
        Assert.assertTrue(localChoose.isFailed());
        Assert.assertEquals("route", localChoose.failure().nodeId());

        DurableResult<String> durableChoose = register(chooseFlow).start("choose-execution", "unknown");
        Assert.assertTrue(durableChoose.isFailed());
        Assert.assertEquals("route", durableChoose.failure().nodeId());
        Assert.assertEquals(IllegalStateException.class.getName(), durableChoose.failure().errorType());
    }

    @Test
    public void successfulRecoveryClearsOldRetryCheckpoint() {
        AtomicInteger childAttempts = new AtomicInteger();
        AtomicInteger childRecoveries = new AtomicInteger();
        AtomicInteger parentAttempts = new AtomicInteger();

        Flow<String, String> child = Flows.<String>begin("recovering-child")
                .step("child-flaky", in -> {
                    if (childAttempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("child failed once");
                    }
                    return in + ":child";
                })
                .recover("child-recover", (in, failure) -> {
                    childRecoveries.incrementAndGet();
                    return FlowResult.succeeded(in + ":recovered");
                })
                .build();

        Flow<String, String> root = Flows.<String>begin("fresh-retry-checkpoint")
                .then(child)
                .step("parent-flaky", in -> {
                    if (parentAttempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("parent failed once");
                    }
                    return in + ":done";
                })
                .build();

        DurableFlow<String, String> durable = register(root);
        DurableResult<String> first = durable.start("retry-execution", "INIT");
        Assert.assertTrue(first.isFailed());
        Assert.assertEquals("parent-flaky", first.failure().nodeId());

        DurableResult<String> retried = durable.retry("retry-execution");
        Assert.assertTrue(retried.isCompleted());
        Assert.assertEquals("INIT:recovered:done", retried.value());
        Assert.assertEquals(1, childAttempts.get());
        Assert.assertEquals(1, childRecoveries.get());
        Assert.assertEquals(2, parentAttempts.get());
    }

    @Test
    public void recoveryMayRethrowOriginalCauseWithoutSelfSuppression() {
        Flow<String, String> flow = Flows.<String>begin("rethrow-original")
                .step("explode", (Step<String, String>) in -> {
                    throw new IllegalStateException("original failure");
                })
                .recover("rethrow", (in, failure) -> {
                    throw (RuntimeException) failure.cause();
                })
                .build();

        DurableResult<String> result = register(flow).start("rethrow-execution", "input");
        Assert.assertTrue(result.isFailed());
        Assert.assertEquals("explode", result.failure().nodeId());
        Assert.assertEquals(IllegalStateException.class.getName(), result.failure().errorType());
        Assert.assertEquals("original failure", result.failure().message());
    }

    @Test
    public void durableInterceptorChainSeesTheCurrentInputLikeLocalExecution() {
        StepInterceptor outer = new StepInterceptor() {
            @Override
            @SuppressWarnings("unchecked")
            public <I, O> O intercept(Chain<I, O> chain) throws Exception {
                I next = (I) (String.valueOf(chain.input()) + ":outer");
                return chain.proceed(next);
            }
        };
        StepInterceptor inner = new StepInterceptor() {
            @Override
            @SuppressWarnings("unchecked")
            public <I, O> O intercept(Chain<I, O> chain) throws Exception {
                I next = (I) (String.valueOf(chain.input()) + ":inner");
                return chain.proceed(next);
            }
        };

        Flow<String, String> flow = Flows.<String>begin("interceptor-parity")
                .interceptor(outer)
                .interceptor(inner)
                .step("business-step", in -> in + ":step")
                .build();

        FlowResult<String> local = flow.run("INIT").result();
        Assert.assertTrue(local.isSucceeded());
        Assert.assertEquals("INIT:outer:inner:step", local.value());

        DurableResult<String> durable = register(flow).start("interceptor-execution", "INIT");
        Assert.assertTrue(durable.isCompleted());
        Assert.assertEquals(local.value(), durable.value());
    }

    private static <I, O> DurableFlow<I, O> register(Flow<I, O> flow) {
        return DurableRuntime.builder(new InMemoryDurableStore())
                .build()
                .register(flow, 1);
    }
}
