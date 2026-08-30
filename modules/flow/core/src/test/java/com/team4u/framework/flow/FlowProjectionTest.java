package com.team4u.framework.flow;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 流程投影 SPI 与结构描述测试。
 *
 * @author jay.wu
 */
public class FlowProjectionTest {

    @Test
    public void describe_containsStructuralMetadataWithoutCallbacks() {
        Flow<String, String> subflow = Flows.<String>begin("sub-1")
                .step("sub-step", in -> in)
                .build();

        Flow<String, String> flow = Flows.<String>begin("main-flow")
                .step("s1", in -> in)
                .tap("t1", in -> {})
                .guard("g1", in -> true, in -> StopReason.of("STOP"))
                .choose("c1", in -> "A")
                    .when("A", Flows.step("branch-a", in -> in))
                    .otherwiseStop(in -> StopReason.of("NO_MATCH"))
                .end()
                .then(subflow)
                .recover("r1", (in, f) -> FlowResult.succeeded("rec"))
                .ensure("e1", (in, c) -> {})
                .build();

        FlowDescription desc = flow.describe();
        Assert.assertEquals("main-flow", desc.flowId());
        Assert.assertEquals(7, desc.nodes().size());

        Assert.assertEquals("s1", desc.nodes().get(0).id());
        Assert.assertEquals(NodeKind.STEP, desc.nodes().get(0).kind());

        Assert.assertEquals("t1", desc.nodes().get(1).id());
        Assert.assertEquals(NodeKind.TAP, desc.nodes().get(1).kind());

        Assert.assertEquals("g1", desc.nodes().get(2).id());
        Assert.assertEquals(NodeKind.GUARD, desc.nodes().get(2).kind());

        Assert.assertEquals("c1", desc.nodes().get(3).id());
        Assert.assertEquals(NodeKind.CHOOSE, desc.nodes().get(3).kind());
        Assert.assertEquals(1, desc.nodes().get(3).branchKeys().size());
        Assert.assertTrue(desc.nodes().get(3).hasOtherwiseStop());

        Assert.assertEquals("sub-1", desc.nodes().get(4).id());
        Assert.assertEquals(NodeKind.SUBFLOW, desc.nodes().get(4).kind());
        Assert.assertNotNull(desc.nodes().get(4).subflow());

        Assert.assertEquals("r1", desc.nodes().get(5).id());
        Assert.assertEquals(NodeKind.RECOVER, desc.nodes().get(5).kind());

        Assert.assertEquals("e1", desc.nodes().get(6).id());
        Assert.assertEquals(NodeKind.ENSURE, desc.nodes().get(6).kind());
    }

    @Test
    public void projection_visitsAllNodesInOrder() {
        Flow<Integer, Integer> flow = Flows.<Integer>begin("proj-test")
                .step("step-1", in -> in + 1)
                .tap("tap-1", in -> {})
                .guard("guard-1", in -> in > 0, in -> StopReason.of("NEG"))
                .build();

        List<String> visited = flow.project(new Flow.Projection<List<String>>() {
            @Override
            public List<String> projectSequence(Flow.SequenceInfo info, List<List<String>> children) {
                List<String> result = new ArrayList<>();
                for (List<String> child : children) {
                    result.addAll(child);
                }
                return result;
            }

            @Override
            public <T, R1> List<String> projectStep(Flow.StepInfo info, Step<T, R1> step, Step.Contextual<T, R1> contextualStep, List<StepInterceptor> interceptors) {
                List<String> list = new ArrayList<>();
                list.add("STEP:" + info.id() + ":" + info.path());
                return list;
            }

            @Override
            public <T> List<String> projectTap(Flow.TapInfo info, Action<T> action, Action.Contextual<T> contextualAction, List<StepInterceptor> interceptors) {
                List<String> list = new ArrayList<>();
                list.add("TAP:" + info.id() + ":" + info.path());
                return list;
            }

            @Override
            public <T> List<String> projectGuard(Flow.GuardInfo info, Condition<T> condition, Function<T, StopReason> reasonFactory) {
                List<String> list = new ArrayList<>();
                list.add("GUARD:" + info.id() + ":" + info.path());
                return list;
            }

            @Override
            public <T, K, R1> List<String> projectChoose(Flow.ChooseInfo<K> info, Function<T, K> selector, Map<K, List<String>> branches, List<String> otherwiseBranch, Function<T, StopReason> otherwiseStopReason) {
                return new ArrayList<>();
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

        Assert.assertEquals(3, visited.size());
        Assert.assertEquals("STEP:step-1:step-1", visited.get(0));
        Assert.assertEquals("TAP:tap-1:tap-1", visited.get(1));
        Assert.assertEquals("GUARD:guard-1:guard-1", visited.get(2));
    }
}
