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

    static class CustomTestKey {
        private final int id;

        CustomTestKey(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CustomTestKey that = (CustomTestKey) o;
            return id == that.id;
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public String toString() {
            return "item";
        }
    }

    @Test
    public void describe_integerAndStringBranchKeys_retainsEveryBranchAndLabelsAreDeterministic() {
        Flow<Object, Object> branchInt = Flows.<Object>begin("b-int")
                .step("step-int", (Step<Object, Object>) in -> in + "-int")
                .build();
        Flow<Object, Object> branchStr = Flows.<Object>begin("b-str")
                .step("step-str", (Step<Object, Object>) in -> in + "-str")
                .build();

        Flow<Object, Object> flow = Flows.begin("choose-int-str")
                .choose("ch1", in -> in)
                .when(Integer.valueOf(1), branchInt)
                .when("1", branchStr)
                .end()
                .build();

        FlowDescription desc1 = flow.describe();
        NodeDescription chooseNode1 = desc1.nodes().get(0);

        Assert.assertEquals(NodeKind.CHOOSE, chooseNode1.kind());
        Assert.assertEquals(2, chooseNode1.branchKeys().size());
        Assert.assertEquals("1 (Integer)", chooseNode1.branchKeys().get(0));
        Assert.assertEquals("1", chooseNode1.branchKeys().get(1));

        Map<String, FlowDescription> branches1 = chooseNode1.branches();
        Assert.assertEquals(2, branches1.size());
        Assert.assertTrue(branches1.containsKey("1 (Integer)"));
        Assert.assertTrue(branches1.containsKey("1"));
        Assert.assertEquals("b-int", branches1.get("1 (Integer)").flowId());
        Assert.assertEquals("b-str", branches1.get("1").flowId());

        // Verify determinism across multiple describe calls
        FlowDescription desc2 = flow.describe();
        NodeDescription chooseNode2 = desc2.nodes().get(0);
        Assert.assertEquals(chooseNode1.branchKeys(), chooseNode2.branchKeys());
        Assert.assertEquals(new ArrayList<>(chooseNode1.branches().keySet()), new ArrayList<>(chooseNode2.branches().keySet()));
    }

    @Test
    public void describe_sameToStringAcrossMultipleTypes_retainsAllBranchesAndPreservesDeclarationOrder() {
        Flow<Object, Object> b1 = Flows.<Object>begin("b1").step("s1", (Step<Object, Object>) in -> in).build();
        Flow<Object, Object> b2 = Flows.<Object>begin("b2").step("s2", (Step<Object, Object>) in -> in).build();
        Flow<Object, Object> b3 = Flows.<Object>begin("b3").step("s3", (Step<Object, Object>) in -> in).build();

        Flow<Object, Object> flow = Flows.begin("choose-multi-type")
                .choose("ch-types", in -> in)
                .when(Integer.valueOf(1), b1)
                .when(Long.valueOf(1L), b2)
                .when("1", b3)
                .end()
                .build();

        FlowDescription desc = flow.describe();
        NodeDescription node = desc.nodes().get(0);

        Assert.assertEquals(3, node.branchKeys().size());
        Assert.assertEquals("1 (Integer)", node.branchKeys().get(0));
        Assert.assertEquals("1 (Long)", node.branchKeys().get(1));
        Assert.assertEquals("1", node.branchKeys().get(2));

        Map<String, FlowDescription> branches = node.branches();
        Assert.assertEquals(3, branches.size());
        List<String> branchKeySetOrder = new ArrayList<>(branches.keySet());
        Assert.assertEquals("1 (Integer)", branchKeySetOrder.get(0));
        Assert.assertEquals("1 (Long)", branchKeySetOrder.get(1));
        Assert.assertEquals("1", branchKeySetOrder.get(2));

        Assert.assertEquals("b1", branches.get("1 (Integer)").flowId());
        Assert.assertEquals("b2", branches.get("1 (Long)").flowId());
        Assert.assertEquals("b3", branches.get("1").flowId());
    }

    @Test
    public void describe_unequalKeysOfSameTypeWithSameToString_retainsAllBranchesWithDeterministicDisambiguation() {
        CustomTestKey k1 = new CustomTestKey(101);
        CustomTestKey k2 = new CustomTestKey(102);

        Flow<Object, Object> bCustom1 = Flows.<Object>begin("b-c1").step("sc1", (Step<Object, Object>) in -> in).build();
        Flow<Object, Object> bCustom2 = Flows.<Object>begin("b-c2").step("sc2", (Step<Object, Object>) in -> in).build();
        Flow<Object, Object> bStr = Flows.<Object>begin("b-str").step("s-str", (Step<Object, Object>) in -> in).build();

        Flow<Object, Object> flow = Flows.begin("choose-same-type")
                .choose("ch-custom", in -> in)
                .when(k1, bCustom1)
                .when(k2, bCustom2)
                .when("item", bStr)
                .end()
                .build();

        FlowDescription desc = flow.describe();
        NodeDescription node = desc.nodes().get(0);

        Assert.assertEquals(3, node.branchKeys().size());
        Assert.assertEquals("item (CustomTestKey#1)", node.branchKeys().get(0));
        Assert.assertEquals("item (CustomTestKey#2)", node.branchKeys().get(1));
        Assert.assertEquals("item", node.branchKeys().get(2));

        Map<String, FlowDescription> branches = node.branches();
        Assert.assertEquals(3, branches.size());
        Assert.assertTrue(branches.containsKey("item (CustomTestKey#1)"));
        Assert.assertTrue(branches.containsKey("item (CustomTestKey#2)"));
        Assert.assertTrue(branches.containsKey("item"));

        Assert.assertEquals("b-c1", branches.get("item (CustomTestKey#1)").flowId());
        Assert.assertEquals("b-c2", branches.get("item (CustomTestKey#2)").flowId());
        Assert.assertEquals("b-str", branches.get("item").flowId());
    }

    @Test
    public void describe_ordinaryStringKeys_keepsLabelsUnchanged() {
        Flow<String, String> bA = Flows.<String>begin("bA").step("sa", in -> in).build();
        Flow<String, String> bB = Flows.<String>begin("bB").step("sb", in -> in).build();
        Flow<String, String> b1 = Flows.<String>begin("b1").step("s1", in -> in).build();
        Flow<String, String> b2 = Flows.<String>begin("b2").step("s2", in -> in).build();

        Flow<String, String> flow = Flows.<String>begin("choose-strings")
                .choose("ch-str", in -> in)
                .when("A", bA)
                .when("B", bB)
                .when("1", b1)
                .when("2", b2)
                .end()
                .build();

        FlowDescription desc = flow.describe();
        NodeDescription node = desc.nodes().get(0);

        Assert.assertEquals(4, node.branchKeys().size());
        Assert.assertEquals("A", node.branchKeys().get(0));
        Assert.assertEquals("B", node.branchKeys().get(1));
        Assert.assertEquals("1", node.branchKeys().get(2));
        Assert.assertEquals("2", node.branchKeys().get(3));

        List<String> keyOrder = new ArrayList<>(node.branches().keySet());
        Assert.assertEquals("A", keyOrder.get(0));
        Assert.assertEquals("B", keyOrder.get(1));
        Assert.assertEquals("1", keyOrder.get(2));
        Assert.assertEquals("2", keyOrder.get(3));
    }

    @Test
    public void executionTokens_remainIndependentOfLabels() {
        List<String> intInvocs = new ArrayList<>();
        List<String> strInvocs = new ArrayList<>();

        Flow<Object, Object> intBranch = Flows.begin("intBranch")
                .step("leaf-node", (ctx, in) -> {
                    intInvocs.add(ctx.invocationId());
                    return in;
                })
                .build();

        Flow<Object, Object> strBranch = Flows.begin("strBranch")
                .step("leaf-node", (ctx, in) -> {
                    strInvocs.add(ctx.invocationId());
                    return in;
                })
                .build();

        Flow<Object, Object> flow = Flows.begin("choose-tokens")
                .choose("select", in -> in)
                .when(Integer.valueOf(1), intBranch)
                .when("1", strBranch)
                .end()
                .build();

        flow.run(Integer.valueOf(1), RunOptions.builder().executionId("exec1").build());
        flow.run("1", RunOptions.builder().executionId("exec1").build());

        Assert.assertEquals(1, intInvocs.size());
        Assert.assertEquals(1, strInvocs.size());

        // Structural tokens are /c:0 and /c:1 regardless of display labels "1 (Integer)" and "1"
        Assert.assertEquals("exec1#/s0:select/c:0/s0:leaf-node", intInvocs.get(0));
        Assert.assertEquals("exec1#/s0:select/c:1/s0:leaf-node", strInvocs.get(0));
    }
}
