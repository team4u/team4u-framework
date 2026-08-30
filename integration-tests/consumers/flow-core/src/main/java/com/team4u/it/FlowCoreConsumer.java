package com.team4u.it;

import com.team4u.framework.flow.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 外部消费者工程验证：确保普通应用只引入 team4u-flow 即可在 Java 8 下编译并执行各种 Flow 能力。
 */
public class FlowCoreConsumer {

    public static void main(String[] args) throws Exception {
        testLambdaAndContextualSteps();
        testDirectInstanceStep();
        testSharedContextAndTypeTransformation();
        testProjectionConsumer();
        testNestedSubflowAndChooseConsumer();
        System.out.println("FlowCoreConsumer executed successfully!");
    }

    private static void testNestedSubflowAndChooseConsumer() {
        final List<String> execIds = new ArrayList<>();
        final List<String> invocIds = new ArrayList<>();

        Flow<String, String> child = Flows.<String>begin("sub-consumer")
                .step("sub-s1", (ctx, in) -> {
                    execIds.add(ctx.executionId());
                    invocIds.add(ctx.invocationId());
                    return in + "-sub";
                })
                .build();

        Flow<String, String> branch = Flows.<String>begin("branch-consumer")
                .step("b-s1", (ctx, in) -> {
                    execIds.add(ctx.executionId());
                    invocIds.add(ctx.invocationId());
                    return in + "-branch";
                })
                .build();

        Flow<String, String> main = Flows.<String>begin("main-consumer")
                .step("p1", (ctx, in) -> {
                    execIds.add(ctx.executionId());
                    invocIds.add(ctx.invocationId());
                    return in + "-p1";
                })
                .then(child)
                .choose("ch", in -> "A")
                .when("A", branch)
                .end()
                .build();

        FlowExecution<String> exec = main.run("hello");
        if (!"hello-p1-sub-branch".equals(exec.result().value())) {
            throw new AssertionError("Unexpected result: " + exec.result().value());
        }
        if (execIds.size() != 3) {
            throw new AssertionError("Expected 3 execution IDs, got: " + execIds.size());
        }
        String rootExecId = exec.executionId();
        for (String id : execIds) {
            if (!rootExecId.equals(id)) {
                throw new AssertionError("Execution ID mismatch: " + id + " vs " + rootExecId);
            }
        }
        if (invocIds.size() != 3 || invocIds.get(0).equals(invocIds.get(1)) || invocIds.get(1).equals(invocIds.get(2))) {
            throw new AssertionError("Invocation IDs should be unique: " + invocIds);
        }
    }

    private static void testLambdaAndContextualSteps() {
        Flow<String, String> flow = Flows.<String>begin("test-lambda")
                .step("step-lambda", in -> in + "-processed")
                .step("step-contextual", (ctx, in) -> {
                    if (ctx.invocationId() == null || ctx.invocationId().isEmpty()) {
                        throw new IllegalStateException("invocationId must not be empty");
                    }
                    return in + "-contextual";
                })
                .build();

        String result = flow.call("hello");
        if (!"hello-processed-contextual".equals(result)) {
            throw new AssertionError("Unexpected result: " + result);
        }
    }

    private static void testDirectInstanceStep() {
        Step<Integer, Integer> doubler = new Step<Integer, Integer>() {
            @Override
            public Integer apply(Integer input) {
                return input * 2;
            }
        };

        Flow<Integer, Integer> flow = Flows.<Integer>begin("test-direct")
                .step("double", doubler)
                .build();

        Integer result = flow.call(21);
        if (result != 42) {
            throw new AssertionError("Unexpected result: " + result);
        }
    }

    private static void testSharedContextAndTypeTransformation() {
        class OrderContext {
            final String orderId;
            boolean reserved;
            OrderContext(String orderId) { this.orderId = orderId; }
        }

        class Receipt {
            final String orderId;
            final boolean success;
            Receipt(String orderId, boolean success) { this.orderId = orderId; this.success = success; }
        }

        Flow<OrderContext, Receipt> flow = Flows.<OrderContext>begin("checkout")
                .tap("reserve", (ctx, order) -> order.reserved = true)
                .step("create-receipt", order -> new Receipt(order.orderId, order.reserved))
                .build();

        OrderContext ctx = new OrderContext("order-100");
        Receipt receipt = flow.call(ctx);
        if (!receipt.success || !"order-100".equals(receipt.orderId)) {
            throw new AssertionError("Unexpected receipt");
        }
    }

    private static void testProjectionConsumer() {
        Flow<String, String> flow = Flows.<String>begin("test-proj")
                .step("s1", in -> in + "-1")
                .step("s2", in -> in + "-2")
                .build();

        List<String> nodeNames = flow.project(new Flow.Projection<List<String>>() {
            @Override
            public List<String> projectSequence(Flow.SequenceInfo info, List<List<String>> children) {
                List<String> all = new ArrayList<>();
                for (List<String> child : children) {
                    all.addAll(child);
                }
                return all;
            }

            @Override
            public <T, R1> List<String> projectStep(Flow.StepInfo info, Step<T, R1> step, Step.Contextual<T, R1> contextualStep, List<StepInterceptor> interceptors) {
                List<String> list = new ArrayList<>();
                list.add(info.id());
                return list;
            }

            @Override
            public <T> List<String> projectTap(Flow.TapInfo info, Action<T> action, Action.Contextual<T> contextualAction, List<StepInterceptor> interceptors) {
                List<String> list = new ArrayList<>();
                list.add(info.id());
                return list;
            }

            @Override
            public <T> List<String> projectGuard(Flow.GuardInfo info, Condition<T> condition, Function<T, StopReason> reasonFactory) {
                List<String> list = new ArrayList<>();
                list.add(info.id());
                return list;
            }

            @Override
            public <T, K, R1> List<String> projectChoose(Flow.ChooseInfo<K> info, Function<T, K> selector, Map<K, List<String>> branches, List<String> otherwiseBranch, Function<T, StopReason> otherwiseStopReason) {
                List<String> list = new ArrayList<>();
                list.add(info.id());
                return list;
            }

            @Override
            public <T, R1> List<String> projectSubflow(Flow.SubflowInfo info, Flow<T, R1> subflow, List<String> subflowProjection) {
                return subflowProjection;
            }

            @Override
            public <T, R1> List<String> projectRecover(Flow.RecoverInfo info, List<String> body, Recovery<T, R1> recovery, Recovery.Contextual<T, R1> contextualRecovery) {
                return body;
            }

            @Override
            public <T, R1> List<String> projectEnsure(Flow.EnsureInfo info, List<String> body, CompletionAction<T, R1> completionAction, CompletionAction.Contextual<T, R1> contextualCompletionAction) {
                return body;
            }
        });

        if (nodeNames.size() != 2 || !"s1".equals(nodeNames.get(0)) || !"s2".equals(nodeNames.get(1))) {
            throw new AssertionError("Unexpected projection result: " + nodeNames);
        }
    }
}
