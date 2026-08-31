package com.team4u.it;

import com.team4u.framework.flow.Branch;
import com.team4u.framework.flow.Failure;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.FlowDescription;
import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.Operation;
import com.team4u.framework.flow.OperationContext;
import com.team4u.framework.flow.OperationResolver;
import com.team4u.framework.flow.Outcome;
import com.team4u.framework.flow.Reason;
import com.team4u.framework.flow.ResumePoint;
import com.team4u.framework.flow.Recovery;
import com.team4u.framework.flow.Resumed;
import com.team4u.framework.flow.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 外部消费者工程验证：确保普通应用只引入 team4u-flow 即可在 Java 8 下编译并执行
 * 新版类型化 Flow 的核心能力（四态 Outcome、then/route/firstApplicable/recoverWith、
 * parallel/join、await/resume、retry/timeout、取消与描述投影）。
 */
public class FlowCoreConsumer {

    private static String acceptedValue(FlowResult<String> result) {
        Outcome<String> outcome = ((FlowResult.Completed<String>) result).outcome();
        if (!(outcome instanceof Outcome.Accepted)) {
            throw new AssertionError("Expected Accepted, got: " + outcome);
        }
        return ((Outcome.Accepted<String>) outcome).value();
    }

    public static void main(String[] args) throws Exception {
        testThenChainAndFourOutcomeKinds();
        testRouteSelection();
        testFirstApplicableAndRecoverWith();
        testParallelJoin();
        testAwaitSuspendAndResume();
        testRetryAndTimeout();
        testDescribeProjection();
        testCancellation();
        System.out.println("FlowCoreConsumer executed successfully!");
    }

    private static void testThenChainAndFourOutcomeKinds() {
        final List<String> invocationIds = new ArrayList<String>();
        Operation<String, String> upper = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                invocationIds.add(context.invocationId());
                return Outcome.accepted(input.toUpperCase());
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(upper)
                .then(new Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context, String input) {
                        return Outcome.accepted(input + "!");
                    }
                });
        FlowResult<String> result = Local.compile(flow).run("hello");
        if (!"HELLO!".equals(acceptedValue(result))) {
            throw new AssertionError("Unexpected result: " + result);
        }
        if (invocationIds.size() != 1 || invocationIds.get(0).isEmpty()) {
            throw new AssertionError("invocationId must be stable: " + invocationIds);
        }

        Flow<String, String> rejected = Flow.<String, String>step(
                new Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context, String input) {
                        return Outcome.rejected(Reason.of("POLICY", "not allowed"));
                    }
                });
        FlowResult<String> rejectedResult = Local.compile(rejected).run("x");
        Outcome<String> outcome = ((FlowResult.Completed<String>) rejectedResult).outcome();
        if (outcome.kind() != Outcome.Kind.REJECTED) {
            throw new AssertionError("Expected REJECTED, got: " + outcome.kind());
        }
    }

    private static void testRouteSelection() {
        Operation<String, String> selector = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input);
            }
        };
        Flow<String, String> flow = Flow.<String, String>route(selector)
                .caseOf("A", Flow.<String, String>accepted("branch-a"))
                .otherwise(Flow.<String, String>rejected(Reason.of("UNKNOWN", "unknown")));
        FlowResult<String> result = Local.compile(flow).run("A");
        if (!"branch-a".equals(acceptedValue(result))) {
            throw new AssertionError("Route failed: " + result);
        }
        FlowResult<String> noMatch = Local.compile(
                Flow.<String, String>route(selector)
                        .caseOf("A", Flow.<String, String>accepted("a"))
                        .withoutOtherwise())
                .run("B");
        Outcome<String> skipped = ((FlowResult.Completed<String>) noMatch).outcome();
        if (skipped.kind() != Outcome.Kind.SKIPPED) {
            throw new AssertionError("Expected SKIPPED no-match, got: " + skipped.kind());
        }
    }

    private static void testFirstApplicableAndRecoverWith() {
        Flow<String, String> applicable = Flow.firstApplicable(
                Flow.<String, String>skipped(Reason.of("NA", "first")),
                Flow.<String, String>accepted("second"));
        FlowResult<String> result = Local.compile(applicable).run("x");
        if (!"second".equals(acceptedValue(result))) {
            throw new AssertionError("firstApplicable failed: " + result);
        }

        final AtomicInteger recoverCalls = new AtomicInteger();
        Flow<String, String> recover = Flow.<String, String>failed(
                Failure.of("BOOM", "boom"))
                .recoverWith(Flow.<Recovery<String>, String>step(new Operation<Recovery<String>, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context, Recovery<String> input) {
                        recoverCalls.incrementAndGet();
                        return Outcome.accepted("recovered:" + input.failure().code());
                    }
                }));
        FlowResult<String> recovered = Local.compile(recover).run("x");
        if (!"recovered:BOOM".equals(acceptedValue(recovered))
                || recoverCalls.get() != 1) {
            throw new AssertionError("recoverWith failed: " + recovered);
        }
    }

    private static void testParallelJoin() {
        Flow<String, String> parallel = Flow.<String>parallel(
                Branch.of("left", Flow.<String, String>accepted("L")),
                Branch.of("right", Flow.<String, String>accepted("R")))
                .join(results -> Outcome.accepted(
                        results.branches().get(0).name() + "+"
                                + results.branches().get(1).name()));
        FlowResult<String> result = Local.compile(parallel).run("x");
        if (!"left+right".equals(acceptedValue(result))) {
            throw new AssertionError("parallel join failed: " + result);
        }
    }

    private static void testAwaitSuspendAndResume() {
        ResumePoint<String> approval = ResumePoint.named("approval");
        Flow<String, Resumed<String, String>> flow =
                Flow.<String, String>step(new Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context, String input) {
                        return Outcome.accepted(input + "-pre");
                    }
                }).await(approval);
        LocalExecutable<String, Resumed<String, String>> executable = Local.compile(flow);
        FlowResult<Resumed<String, String>> suspended = executable.run("x");
        if (!(suspended instanceof FlowResult.Suspended)) {
            throw new AssertionError("Expected Suspended, got: " + suspended);
        }
        FlowResult.Suspended<Resumed<String, String>> suspension =
                (FlowResult.Suspended<Resumed<String, String>>) suspended;
        FlowResult<Resumed<String, String>> resumed = executable.resume(
                suspension.suspension(), approval, "approved");
        Outcome<Resumed<String, String>> resumedOutcome =
                ((FlowResult.Completed<Resumed<String, String>>) resumed).outcome();
        Resumed<String, String> value = ((Outcome.Accepted<Resumed<String, String>>) resumedOutcome).value();
        if (!"x-pre".equals(value.state()) || !"approved".equals(value.signal())) {
            throw new AssertionError("Resume failed: " + value.state() + "/" + value.signal());
        }
    }

    private static void testRetryAndTimeout() {
        final AtomicInteger attempts = new AtomicInteger();
        Flow<String, String> retryFlow = Flow.<String, String>step(new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (attempts.incrementAndGet() < 3) {
                    return Outcome.failed(Failure.of("FLAKY", "try again"));
                }
                return Outcome.accepted("ok@" + attempts.get());
            }
        }).retry(Retry.maxAttempts(3));
        FlowResult<String> retried = Local.compile(retryFlow).run("x");
        if (!"ok@3".equals(acceptedValue(retried))) {
            throw new AssertionError("retry failed: " + retried);
        }

        Flow<String, String> timedOut = Flow.<String, String>step(
                new Operation<String, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context, String input) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ignored) {
                        }
                        return Outcome.accepted("late");
                    }
                }).timeout(Duration.ofMillis(100));
        FlowResult<String> timedOutResult = Local.compile(timedOut).run("x");
        Outcome<String> timedOutcome =
                ((FlowResult.Completed<String>) timedOutResult).outcome();
        if (timedOutcome.kind() != Outcome.Kind.FAILED
                || !"TIMEOUT".equals(((Outcome.Failed<String>) timedOutcome).failure().code())) {
            throw new AssertionError("timeout failed: " + timedOutcome);
        }
    }

    private static void testDescribeProjection() {
        Flow<String, String> flow = Flow.<String, String>step(new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input);
            }
        }).then(Flow.<String, String>accepted("done"));
        FlowDescription description = flow.describe();
        if (description.root().kind() != com.team4u.framework.flow.NodeDescriptor.Kind.SEQUENCE) {
            throw new AssertionError("Expected SEQUENCE root: " + description.root().kind());
        }
        if (description.root().children().size() != 2) {
            throw new AssertionError("Expected 2 children: "
                    + description.root().children().size());
        }
    }

    private static void testCancellation() {
        Flow<String, String> flow = Flow.<String, String>step(new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                context.cancellation().throwIfCancelled();
                return Outcome.accepted(input);
            }
        });
        com.team4u.framework.flow.Cancellation cancellation = com.team4u.framework.flow.Cancellation.create();
        cancellation.cancel();
        LocalExecutable<String, String> executable = Local.compile(flow);
        FlowResult<String> result = executable.run("x", cancellation);
        if (!(result instanceof FlowResult.Cancelled)) {
            throw new AssertionError("Expected Cancelled, got: " + result);
        }
    }
}
