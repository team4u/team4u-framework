package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.FlowDescription;
import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.Flows;
import com.team4u.framework.flow.StopReason;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流程图渲染测试。
 *
 * @author jay.wu
 */
public class FlowGraphTest {

    @Test
    public void mermaid_rendersCompleteStructure() {
        Flow<String, String> subflow = Flows.<String>begin("sub-payment")
                .step("pay-step", in -> in)
                .build();

        Flow<String, String> flow = Flows.<String>begin("checkout-flow")
                .guard("check-order", in -> true, in -> StopReason.of("INVALID"))
                .tap("reserve-stock", in -> {})
                .step("create-order", in -> in)
                .choose("pay-channel", in -> in)
                    .when("CARD", Flows.step("card-step", in -> in))
                    .when("WALLET", Flows.step("wallet-step", in -> in))
                    .otherwiseStop(in -> StopReason.of("NO_CHANNEL"))
                .end()
                .then(subflow)
                .recover("on-failure", (in, f) -> FlowResult.succeeded("rec"))
                .ensure("cleanup", (in, c) -> {})
                .build();

        FlowDescription desc = flow.describe();
        String mermaid = FlowGraphs.mermaid().render(desc);

        Assert.assertNotNull(mermaid);
        Assert.assertTrue(mermaid.startsWith("flowchart TD"));
        Assert.assertTrue(mermaid.contains("Start: checkout-flow"));
        Assert.assertTrue(mermaid.contains("Guard: check-order"));
        Assert.assertTrue(mermaid.contains("Tap: reserve-stock"));
        Assert.assertTrue(mermaid.contains("Step: create-order"));
        Assert.assertTrue(mermaid.contains("Choose: pay-channel"));
        Assert.assertTrue(mermaid.contains("Subflow: sub-payment"));
        Assert.assertTrue(mermaid.contains("Recover: on-failure"));
        Assert.assertTrue(mermaid.contains("Ensure: cleanup"));
        Assert.assertTrue(mermaid.contains("End: checkout-flow"));

        // Verify valid styling syntax and no invalid .style
        Assert.assertFalse(mermaid.contains(".style"));
        Assert.assertTrue(mermaid.contains("style stop_"));

        // Verify failure route to recover and recover route to ensure
        Assert.assertTrue(mermaid.contains("-.->|on failure| node_"));
        Assert.assertTrue(mermaid.contains("[\"Recover: on-failure\"]"));
        Assert.assertTrue(mermaid.contains("[\"Ensure: cleanup\"]"));
        Assert.assertTrue(mermaid.contains("End: checkout-flow"));
    }

    @Test
    public void mermaid_recoverOnlyOnFailure_normalBypasses_noEnsure() {
        Flow<String, String> flow = Flows.<String>begin("rec-no-ens")
                .step("step-a", in -> in)
                .step("step-b", in -> in)
                .recover("fallback", (in, f) -> FlowResult.succeeded("recovered"))
                .build();

        FlowDescription desc = flow.describe();
        String mermaid = FlowGraphs.mermaid().render(desc);

        // Find node IDs
        Pattern stepBPattern = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: step-b\"\\]");
        Matcher stepBMatcher = stepBPattern.matcher(mermaid);
        Assert.assertTrue(stepBMatcher.find());
        String stepBId = stepBMatcher.group(1);

        Pattern recPattern = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Recover: fallback\"\\]");
        Matcher recMatcher = recPattern.matcher(mermaid);
        Assert.assertTrue(recMatcher.find());
        String recId = recMatcher.group(1);

        Pattern endPattern = Pattern.compile("(end_\\d+)\\(");
        Matcher endMatcher = endPattern.matcher(mermaid);
        Assert.assertTrue(endMatcher.find());
        String endId = endMatcher.group(1);

        // Normal success bypasses recover and reaches End.
        Assert.assertTrue(mermaid.contains(stepBId + " --> " + endId));

        // Every body failure can enter recover; recovered success reaches End through
        // an outcome-specific gateway.
        Assert.assertTrue(mermaid.contains(stepBId + " -.->|on failure| " + recId));
        Assert.assertTrue(pathExists(mermaid, recId, endId));

        // Normal flow must not enter recover.
        Assert.assertFalse(mermaid.contains(stepBId + " --> " + recId));
    }

    @Test
    public void mermaid_ensureOnEveryTerminalOutcome() {
        Flow<String, String> flow = Flows.<String>begin("ens-all-paths")
                .guard("guard-check", in -> true, in -> StopReason.of("REJECTED"))
                .step("do-work", in -> in)
                .recover("on-err", (in, f) -> FlowResult.succeeded("ok"))
                .ensure("always-cleanup", (in, c) -> {})
                .build();

        FlowDescription desc = flow.describe();
        String mermaid = FlowGraphs.mermaid().render(desc);

        Pattern workPattern = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: do-work\"\\]");
        Matcher workMatcher = workPattern.matcher(mermaid);
        Assert.assertTrue(workMatcher.find());
        String workId = workMatcher.group(1);

        Pattern recPattern = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Recover: on-err\"\\]");
        Matcher recMatcher = recPattern.matcher(mermaid);
        Assert.assertTrue(recMatcher.find());
        String recId = recMatcher.group(1);

        String successEnsureId = findProcessNodeId(mermaid, "Ensure: always-cleanup");
        String stoppedEnsureId = findProcessNodeId(mermaid, "Ensure: always-cleanup [stopped]");

        Pattern stopPattern = Pattern.compile("(stop_\\d+)\\(\\[\"STOPPED\"\\]\\)");
        Matcher stopMatcher = stopPattern.matcher(mermaid);
        Assert.assertTrue(stopMatcher.find());
        String stopId = stopMatcher.group(1);

        Pattern endPattern = Pattern.compile("(end_\\d+)\\(");
        Matcher endMatcher = endPattern.matcher(mermaid);
        Assert.assertTrue(endMatcher.find());
        String endId = endMatcher.group(1);

        // Success and stopped outcomes use distinct ensure lanes. This keeps a
        // stopped path from becoming reachable to the success End.
        Assert.assertTrue(mermaid.contains(workId + " -->|success| " + successEnsureId));
        Assert.assertTrue(mermaid.contains(workId + " -.->|on failure| " + recId));
        Assert.assertTrue(pathExists(mermaid, recId, successEnsureId));
        Assert.assertTrue(mermaid.contains(stopId + " -->|stopped| " + stoppedEnsureId));
        Assert.assertTrue(pathExists(mermaid, successEnsureId, endId));
        Assert.assertFalse(pathExists(mermaid, stopId, endId));
    }

    @Test
    public void mermaid_guardStoppedWithoutEnsure_isTerminal() {
        Flow<String, String> flow = Flows.<String>begin("guard-standalone")
                .guard("g-check", in -> true, in -> StopReason.of("HALT"))
                .step("after-guard", in -> in)
                .build();

        FlowDescription desc = flow.describe();
        String mermaid = FlowGraphs.mermaid().render(desc);

        Pattern stopPattern = Pattern.compile("(stop_\\d+)\\(\\[\"STOPPED\"\\]\\)");
        Matcher stopMatcher = stopPattern.matcher(mermaid);
        Assert.assertTrue(stopMatcher.find());
        String stopId = stopMatcher.group(1);

        // Valid style directive emitted
        Assert.assertTrue(mermaid.contains("style " + stopId + " fill:#f9f,stroke:#333"));
        Assert.assertFalse(mermaid.contains(stopId + "([\"STOPPED\"]).style"));

        // Stopped node is terminal without ensure, does not connect to end
        Assert.assertFalse(mermaid.contains(stopId + " --> end_"));
    }

    @Test
    public void mermaid_chooseWithBranchesOtherwiseAndOtherwiseStop() {
        // Choose with regular branches and otherwise flow
        Flow<String, String> flowWithOtherwise = Flows.<String>begin("choose-otherwise")
                .choose("route", in -> in)
                    .when("X", Flows.step("step-x", in -> in))
                    .otherwise(Flows.step("step-default", in -> in))
                .end()
                .build();

        String mermaid1 = FlowGraphs.mermaid().render(flowWithOtherwise.describe());
        Assert.assertTrue(mermaid1.contains("-->|\"X\"|"));
        Assert.assertTrue(mermaid1.contains("-->|otherwise|"));
        Assert.assertTrue(mermaid1.contains("[\"Step: step-x\"]"));
        Assert.assertTrue(mermaid1.contains("[\"Step: step-default\"]"));

        // Choose with otherwiseStop and ensure
        Flow<String, String> flowWithOtherwiseStop = Flows.<String>begin("choose-stop-ens")
                .choose("route", in -> in)
                    .when("A", Flows.step("step-a", in -> in))
                    .otherwiseStop(in -> StopReason.of("UNKNOWN"))
                .end()
                .ensure("clean", (in, c) -> {})
                .build();

        String mermaid2 = FlowGraphs.mermaid().render(flowWithOtherwiseStop.describe());
        Assert.assertTrue(mermaid2.contains("-->|otherwise| stop_"));
        Assert.assertTrue(mermaid2.contains("style stop_"));

        Pattern stopPat = Pattern.compile("(stop_\\d+)\\(\\[\"STOPPED\"\\]\\)");
        Matcher stopMat = stopPat.matcher(mermaid2);
        Assert.assertTrue(stopMat.find());
        String stopId = stopMat.group(1);

        String stoppedEnsureId = findProcessNodeId(mermaid2, "Ensure: clean [stopped]");
        Assert.assertTrue(mermaid2.contains(stopId + " -->|stopped| " + stoppedEnsureId));
    }

    @Test
    public void mermaid_selectorLabelsWithSpecialCharacters() {
        Flow<String, String> flow = Flows.<String>begin("special-choose")
                .choose("selector", in -> in)
                    .when("CARD|WALLET", Flows.step("step-multi", in -> in))
                    .when("TYPE\"A\"", Flows.step("step-quote", in -> in))
                    .when("LINE1\nLINE2", Flows.step("step-nl", in -> in))
                .end()
                .build();

        String mermaid = FlowGraphs.mermaid().render(flow.describe());

        // Vertical bar escaped in edge label
        Assert.assertTrue(mermaid.contains("CARD#124;WALLET"));
        // Quote escaped in edge label
        Assert.assertTrue(mermaid.contains("TYPE#quot;A#quot;"));
        // Newline converted in edge label
        Assert.assertTrue(mermaid.contains("LINE1<br/>LINE2"));
    }

    @Test
    public void mermaid_escapesSpecialCharactersInLabelsAndIds() {
        Flow<String, String> flow = Flows.<String>begin("test\"flow<special>\\path\nline2")
                .step("step\"with[quotes]&brackets\\and\nnewlines", in -> in)
                .build();

        FlowDescription desc = flow.describe();
        String mermaid = FlowGraphs.mermaid().render(desc);

        Assert.assertFalse(mermaid.contains("\"step\"with"));
        Assert.assertTrue(mermaid.contains("#quot;"));
        Assert.assertTrue(mermaid.contains("#92;"));
        Assert.assertTrue(mermaid.contains("<br/>"));
        // Verify no raw multi-line breaks in node text that would corrupt syntax
        for (String line : mermaid.split("\n")) {
            if (line.contains("[\"") || line.contains("([\"") || line.contains("{\"")) {
                Assert.assertTrue("Line must be properly terminated: " + line,
                        line.endsWith("\"]") || line.endsWith("\"]\n") ||
                        line.endsWith("\"])") || line.endsWith("\"])\n") ||
                        line.endsWith("\"}") || line.endsWith("\"}\n"));
            }
        }
    }

    @Test
    public void mermaid_repeatedNodeIdsInDifferentScopes_noCollisions() {
        Flow<String, String> subflow1 = Flows.<String>begin("sub1")
                .step("calc", in -> in)
                .build();

        Flow<String, String> subflow2 = Flows.<String>begin("sub2")
                .step("calc", in -> in)
                .build();

        Flow<String, String> flow = Flows.<String>begin("scope-test")
                .step("calc", in -> in)
                .choose("choice", in -> in)
                    .when("A", Flows.step("calc", in -> in))
                    .when("B", Flows.step("calc", in -> in))
                .end()
                .then(subflow1)
                .then(subflow2)
                .build();

        FlowDescription desc = flow.describe();
        String mermaid = FlowGraphs.mermaid().render(desc);

        // Collect all declared node IDs in flowchart
        Pattern declPattern = Pattern.compile("^\\s*([a-zA-Z0-9_]+)\\[\"Step: calc\"\\]", Pattern.MULTILINE);
        Matcher matcher = declPattern.matcher(mermaid);
        Set<String> declaredIds = new HashSet<>();
        int count = 0;
        while (matcher.find()) {
            String id = matcher.group(1);
            boolean added = declaredIds.add(id);
            Assert.assertTrue("Node ID must be unique: " + id, added);
            count++;
        }
        // There should be 5 distinct "calc" step nodes (main, branch A, branch B, subflow1, subflow2)
        Assert.assertEquals(5, count);
        Assert.assertEquals(5, declaredIds.size());
    }

    @Test
    public void mermaid_nestedSubflows() {
        Flow<String, String> innerSub = Flows.<String>begin("inner-sub")
                .step("inner-step", in -> in)
                .build();

        Flow<String, String> outerSub = Flows.<String>begin("outer-sub")
                .step("outer-step-1", in -> in)
                .then(innerSub)
                .step("outer-step-2", in -> in)
                .build();

        Flow<String, String> mainFlow = Flows.<String>begin("main-flow")
                .step("main-start", in -> in)
                .then(outerSub)
                .step("main-end", in -> in)
                .build();

        String mermaid = FlowGraphs.mermaid().render(mainFlow.describe());

        Assert.assertTrue(mermaid.contains("Subflow: outer-sub"));
        Assert.assertTrue(mermaid.contains("Subflow: inner-sub"));
        Assert.assertTrue(mermaid.contains("Step: inner-step"));
        Assert.assertTrue(mermaid.contains("Step: outer-step-1"));
        Assert.assertTrue(mermaid.contains("Step: outer-step-2"));
        Assert.assertTrue(mermaid.contains("Step: main-start"));
        Assert.assertTrue(mermaid.contains("Step: main-end"));
    }

    @Test
    public void mermaid_nestedSubflowEnsureAndParentEnsure() {
        Flow<String, String> subflow = Flows.<String>begin("sub-with-ensure")
                .guard("sub-guard", in -> true, in -> StopReason.of("SUB_STOP"))
                .step("sub-work", in -> in)
                .ensure("sub-cleanup", (in, c) -> {})
                .build();

        Flow<String, String> mainFlow = Flows.<String>begin("main-with-ensure")
                .step("main-start", in -> in)
                .then(subflow)
                .step("main-after", in -> in)
                .ensure("main-cleanup", (in, c) -> {})
                .build();

        String mermaid = FlowGraphs.mermaid().render(mainFlow.describe());

        Pattern subGuardPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\{\"Guard: sub-guard\"\\}");
        Matcher subGuardMat = subGuardPat.matcher(mermaid);
        Assert.assertTrue(subGuardMat.find());
        String subGuardId = subGuardMat.group(1);

        Pattern subStopPat = Pattern.compile("(stop_\\d+)\\(\\[\"STOPPED\"\\]\\)");
        Matcher subStopMat = subStopPat.matcher(mermaid);
        Assert.assertTrue(subStopMat.find());
        String subStopId = subStopMat.group(1);

        String subSuccessEnsureId = findProcessNodeId(mermaid, "Ensure: sub-cleanup");
        String subStoppedEnsureId = findProcessNodeId(mermaid, "Ensure: sub-cleanup [stopped]");
        String mainStoppedEnsureId = findProcessNodeId(mermaid, "Ensure: main-cleanup [stopped]");

        Pattern mainAfterPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: main-after\"\\]");
        Matcher mainAfterMat = mainAfterPat.matcher(mermaid);
        Assert.assertTrue(mainAfterMat.find());
        String mainAfterId = mainAfterMat.group(1);

        Assert.assertTrue(mermaid.contains(subGuardId + " -->|stopped| " + subStopId));
        Assert.assertTrue(mermaid.contains(subStopId + " -->|stopped| " + subStoppedEnsureId));

        // Stopped and success outcomes leave the subflow through distinct lanes.
        Assert.assertTrue(pathExists(mermaid, subStoppedEnsureId, mainStoppedEnsureId));
        Assert.assertTrue(pathExists(mermaid, subSuccessEnsureId, mainAfterId));
        Assert.assertFalse(pathExists(mermaid, subStopId, mainAfterId));
        Assert.assertFalse(pathExists(mermaid, subStopId, findEndId(mermaid)));
    }

    @Test
    public void mermaid_nestedRecoverWithoutEnsure_convergesToContinuation() {
        Flow<String, String> subflow = Flows.<String>begin("sub-rec-only")
                .step("sub-step", in -> in)
                .recover("sub-fallback", (in, f) -> FlowResult.succeeded("rec-ok"))
                .build();

        Flow<String, String> mainFlow = Flows.<String>begin("main-seq")
                .step("before-sub", in -> in)
                .then(subflow)
                .step("after-sub", in -> in)
                .build();

        String mermaid = FlowGraphs.mermaid().render(mainFlow.describe());

        Pattern beforePat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: before-sub\"\\]");
        Matcher beforeMat = beforePat.matcher(mermaid);
        Assert.assertTrue(beforeMat.find());
        String beforeId = beforeMat.group(1);

        Pattern subStepPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: sub-step\"\\]");
        Matcher subStepMat = subStepPat.matcher(mermaid);
        Assert.assertTrue(subStepMat.find());
        String subStepId = subStepMat.group(1);

        Pattern subRecPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Recover: sub-fallback\"\\]");
        Matcher subRecMat = subRecPat.matcher(mermaid);
        Assert.assertTrue(subRecMat.find());
        String subRecId = subRecMat.group(1);

        Pattern afterPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: after-sub\"\\]");
        Matcher afterMat = afterPat.matcher(mermaid);
        Assert.assertTrue(afterMat.find());
        String afterId = afterMat.group(1);

        // 1. Entry into subflow
        Assert.assertTrue(mermaid.contains(beforeId + " --> " + subStepId));

        // 2. Failure edge to recover inside subflow
        Assert.assertTrue(mermaid.contains(subStepId + " -.->|on failure| " + subRecId));

        // Normal and recovered success converge on the continuation through
        // distinct recover outcome gateways.
        Assert.assertTrue(mermaid.contains(subStepId + " --> " + afterId));
        Assert.assertTrue(pathExists(mermaid, subRecId, afterId));
        Assert.assertFalse(mermaid.contains(subStepId + " --> " + subRecId));
    }

    @Test
    public void mermaid_branchGuardStop_routesToParentEnsureAndBypassesContinuation() {
        Flow<String, String> flow = Flows.<String>begin("branch-guard-flow")
                .choose("branch-selector", in -> in)
                    .when("FAST", Flows.step("fast-step", in -> in))
                    .when("SECURE", Flows.<String>begin("secure-branch")
                            .guard("sec-guard", in -> true, in -> StopReason.of("UNAUTHORIZED"))
                            .step("sec-step", in -> in)
                            .build())
                .end()
                .step("after-choose", in -> in)
                .ensure("audit-clean", (in, c) -> {})
                .build();

        String mermaid = FlowGraphs.mermaid().render(flow.describe());

        Pattern stopPat = Pattern.compile("(stop_\\d+)\\(\\[\"STOPPED\"\\]\\)");
        Matcher stopMat = stopPat.matcher(mermaid);
        Assert.assertTrue(stopMat.find());
        String stopId = stopMat.group(1);

        String stoppedEnsureId = findProcessNodeId(mermaid, "Ensure: audit-clean [stopped]");

        Pattern joinPat = Pattern.compile("(join_\\d+)\\(\\(\\s*\\)\\)");
        Matcher joinMat = joinPat.matcher(mermaid);
        Assert.assertTrue(joinMat.find());
        String joinId = joinMat.group(1);

        Pattern afterPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: after-choose\"\\]");
        Matcher afterMat = afterPat.matcher(mermaid);
        Assert.assertTrue(afterMat.find());
        String afterId = afterMat.group(1);

        Pattern secStepPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: sec-step\"\\]");
        Matcher secStepMat = secStepPat.matcher(mermaid);
        Assert.assertTrue(secStepMat.find());
        String secStepId = secStepMat.group(1);

        Assert.assertTrue(pathExists(mermaid, stopId, stoppedEnsureId));
        Assert.assertTrue(mermaid.contains(joinId + " --> " + afterId));
        Assert.assertFalse(pathExists(mermaid, stopId, joinId));
        Assert.assertFalse(pathExists(mermaid, stopId, afterId));
        Assert.assertFalse(pathExists(mermaid, stopId, secStepId));
    }

    @Test
    public void mermaid_failureFromEarlyBodyNode_routesToRecover() {
        Flow<String, String> flow = Flows.<String>begin("multi-step-recover")
                .step("step-1", in -> in)
                .step("step-2", in -> in)
                .step("step-3", in -> in)
                .recover("handle-err", (in, f) -> FlowResult.succeeded("recovered"))
                .ensure("clean-all", (in, c) -> {})
                .build();

        String mermaid = FlowGraphs.mermaid().render(flow.describe());

        Pattern step1Pat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: step-1\"\\]");
        Matcher step1Mat = step1Pat.matcher(mermaid);
        Assert.assertTrue(step1Mat.find());
        String step1Id = step1Mat.group(1);

        Pattern step2Pat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: step-2\"\\]");
        Matcher step2Mat = step2Pat.matcher(mermaid);
        Assert.assertTrue(step2Mat.find());
        String step2Id = step2Mat.group(1);

        Pattern step3Pat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: step-3\"\\]");
        Matcher step3Mat = step3Pat.matcher(mermaid);
        Assert.assertTrue(step3Mat.find());
        String step3Id = step3Mat.group(1);

        Pattern recPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Recover: handle-err\"\\]");
        Matcher recMat = recPat.matcher(mermaid);
        Assert.assertTrue(recMat.find());
        String recId = recMat.group(1);

        String successEnsureId = findProcessNodeId(mermaid, "Ensure: clean-all");

        // Sequential normal execution reaches the success ensure lane.
        Assert.assertTrue(mermaid.contains(step1Id + " --> " + step2Id));
        Assert.assertTrue(mermaid.contains(step2Id + " --> " + step3Id));
        Assert.assertTrue(mermaid.contains(step3Id + " -->|success| " + successEnsureId));

        // 2. Early and late body nodes route failure to recover
        Assert.assertTrue(mermaid.contains(step1Id + " -.->|on failure| " + recId));
        Assert.assertTrue(mermaid.contains(step2Id + " -.->|on failure| " + recId));
        Assert.assertTrue(mermaid.contains(step3Id + " -.->|on failure| " + recId));

        // Recovered success reaches the success ensure lane.
        Assert.assertTrue(pathExists(mermaid, recId, successEnsureId));

        // 4. Absence: no normal success edges from steps to recover
        Assert.assertFalse(mermaid.contains(step1Id + " --> " + recId));
        Assert.assertFalse(mermaid.contains(step2Id + " --> " + recId));
        Assert.assertFalse(mermaid.contains(step3Id + " --> " + recId));
    }

    @Test
    public void mermaid_repeatedScopesAndNodes_noCollisionsOrSpuriousEdges() {
        Flow<String, String> reusableSub = Flows.<String>begin("shared-sub")
                .guard("check", in -> true, in -> StopReason.of("STOPPED"))
                .step("process", in -> in)
                .ensure("sub-clean", (in, c) -> {})
                .build();

        Flow<String, String> mainFlow = Flows.<String>begin("repeated-flow")
                .then(reusableSub)
                .then(reusableSub)
                .ensure("root-clean", (in, c) -> {})
                .build();

        String mermaid = FlowGraphs.mermaid().render(mainFlow.describe());

        // Collect all subgraphs and ensure distinct IDs
        Pattern subPattern = Pattern.compile("subgraph\\s+([a-zA-Z0-9_]+)");
        Matcher subMatcher = subPattern.matcher(mermaid);
        Set<String> subIds = new HashSet<>();
        int subCount = 0;
        while (subMatcher.find()) {
            subCount++;
            Assert.assertTrue("Subgraph ID must be unique: " + subMatcher.group(1), subIds.add(subMatcher.group(1)));
        }
        Assert.assertEquals(2, subCount);

        // Collect all process step node IDs and ensure distinct
        Pattern processPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: process\"\\]");
        Matcher processMat = processPat.matcher(mermaid);
        Set<String> processIds = new HashSet<>();
        int processCount = 0;
        while (processMat.find()) {
            processCount++;
            Assert.assertTrue("Process node ID must be unique: " + processMat.group(1), processIds.add(processMat.group(1)));
        }
        Assert.assertEquals(2, processCount);

        // Collect all guard STOPPED node IDs and ensure distinct
        Pattern stopPat = Pattern.compile("(stop_\\d+)\\(\\[\"STOPPED\"\\]\\)");
        Matcher stopMat = stopPat.matcher(mermaid);
        Set<String> stopIds = new HashSet<>();
        int stopCount = 0;
        while (stopMat.find()) {
            stopCount++;
            Assert.assertTrue("Stop node ID must be unique: " + stopMat.group(1), stopIds.add(stopMat.group(1)));
        }
        Assert.assertEquals(2, stopCount);
    }

    @Test
    public void mermaid_nestedSubflowWithoutEnsure_parentEnsure() {
        Flow<String, String> subflow = Flows.<String>begin("sub-no-ens")
                .guard("sub-g", in -> true, in -> StopReason.of("STOPPED_IN_SUB"))
                .step("sub-s", in -> in)
                .build();

        Flow<String, String> mainFlow = Flows.<String>begin("main-with-ens")
                .then(subflow)
                .step("main-step", in -> in)
                .ensure("main-ens", (in, c) -> {})
                .build();

        String mermaid = FlowGraphs.mermaid().render(mainFlow.describe());

        Pattern stopPat = Pattern.compile("(stop_\\d+)\\(\\[\"STOPPED\"\\]\\)");
        Matcher stopMat = stopPat.matcher(mermaid);
        Assert.assertTrue(stopMat.find());
        String stopId = stopMat.group(1);

        String stoppedEnsureId = findProcessNodeId(mermaid, "Ensure: main-ens [stopped]");

        Pattern mainStepPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: main-step\"\\]");
        Matcher mainStepMat = mainStepPat.matcher(mermaid);
        Assert.assertTrue(mainStepMat.find());
        String mainStepId = mainStepMat.group(1);

        Assert.assertTrue(pathExists(mermaid, stopId, stoppedEnsureId));
        Assert.assertFalse(pathExists(mermaid, stopId, mainStepId));
        Assert.assertFalse(pathExists(mermaid, stopId, findEndId(mermaid)));
    }

    @Test
    public void mermaid_branchWithEnsure_routesToParentEnsure() {
        Flow<String, String> branchFlow = Flows.<String>begin("branch-ens-flow")
                .guard("branch-g", in -> true, in -> StopReason.of("STOP_IN_BRANCH"))
                .step("branch-s", in -> in)
                .ensure("branch-ens", (in, c) -> {})
                .build();

        Flow<String, String> mainFlow = Flows.<String>begin("main-ens-flow")
                .choose("branch-choice", in -> in)
                    .when("BRANCH_1", branchFlow)
                    .otherwise(Flows.step("other-s", in -> in))
                .end()
                .step("after-all", in -> in)
                .ensure("parent-ens", (in, c) -> {})
                .build();

        String mermaid = FlowGraphs.mermaid().render(mainFlow.describe());

        Pattern stopPat = Pattern.compile("(stop_\\d+)\\(\\[\"STOPPED\"\\]\\)");
        Matcher stopMat = stopPat.matcher(mermaid);
        Assert.assertTrue(stopMat.find());
        String stopId = stopMat.group(1);

        String branchStoppedEnsureId = findProcessNodeId(mermaid, "Ensure: branch-ens [stopped]");
        String parentStoppedEnsureId = findProcessNodeId(mermaid, "Ensure: parent-ens [stopped]");

        Pattern joinPat = Pattern.compile("(join_\\d+)\\(\\(\\s*\\)\\)");
        Matcher joinMat = joinPat.matcher(mermaid);
        Assert.assertTrue(joinMat.find());
        String joinId = joinMat.group(1);

        Assert.assertTrue(pathExists(mermaid, stopId, branchStoppedEnsureId));
        Assert.assertTrue(pathExists(mermaid, branchStoppedEnsureId, parentStoppedEnsureId));
        Assert.assertFalse(pathExists(mermaid, stopId, joinId));
    }

    @Test
    public void mermaid_subflowFailurePropagatesToParentRecover() {
        Flow<String, String> subflow = Flows.<String>begin("sub-fail-unhandled")
                .step("sub-step-1", in -> in)
                .step("sub-step-2", in -> in)
                .build();

        Flow<String, String> mainFlow = Flows.<String>begin("main-recover-flow")
                .then(subflow)
                .step("main-step-after", in -> in)
                .recover("parent-fallback", (in, f) -> FlowResult.succeeded("handled"))
                .build();

        String mermaid = FlowGraphs.mermaid().render(mainFlow.describe());

        Pattern sub1Pat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: sub-step-1\"\\]");
        Matcher sub1Mat = sub1Pat.matcher(mermaid);
        Assert.assertTrue(sub1Mat.find());
        String sub1Id = sub1Mat.group(1);

        Pattern sub2Pat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: sub-step-2\"\\]");
        Matcher sub2Mat = sub2Pat.matcher(mermaid);
        Assert.assertTrue(sub2Mat.find());
        String sub2Id = sub2Mat.group(1);

        Pattern mainStepPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Step: main-step-after\"\\]");
        Matcher mainStepMat = mainStepPat.matcher(mermaid);
        Assert.assertTrue(mainStepMat.find());
        String mainStepId = mainStepMat.group(1);

        Pattern recPat = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\"Recover: parent-fallback\"\\]");
        Matcher recMat = recPat.matcher(mermaid);
        Assert.assertTrue(recMat.find());
        String recId = recMat.group(1);

        // 1. Both subflow steps route failure to parent recover
        Assert.assertTrue(mermaid.contains(sub1Id + " -.->|on failure| " + recId));
        Assert.assertTrue(mermaid.contains(sub2Id + " -.->|on failure| " + recId));
        Assert.assertTrue(mermaid.contains(mainStepId + " -.->|on failure| " + recId));

        // 2. Absence: subflow steps must not have normal success edge to recover
        Assert.assertFalse(mermaid.contains(sub1Id + " --> " + recId));
        Assert.assertFalse(mermaid.contains(sub2Id + " --> " + recId));
    }

    @Test
    public void text_rendersCompleteTreeStructure() {
        Flow<String, String> subflow = Flows.<String>begin("sub-payment")
                .step("pay-step", in -> in)
                .build();

        Flow<String, String> flow = Flows.<String>begin("checkout")
                .guard("validate-order", in -> true, in -> StopReason.of("INVALID"))
                .tap("reserve-stock", in -> {})
                .choose("choose-channel", in -> in)
                    .when("CARD", Flows.tap("call-card-gateway", in -> {}))
                    .when("WALLET", Flows.tap("call-wallet-gateway", in -> {}))
                    .otherwiseStop(in -> StopReason.of("NO_CHANNEL"))
                .end()
                .then(subflow)
                .step("build-receipt", in -> in)
                .recover("fallback", (in, f) -> FlowResult.succeeded("rec"))
                .ensure("cleanup-metrics", (in, c) -> {})
                .build();

        FlowDescription desc = flow.describe();
        String text = FlowGraphs.text().render(desc);

        Assert.assertNotNull(text);
        Assert.assertTrue(text.startsWith("Flow: checkout\n"));
        Assert.assertTrue(text.contains("├── GUARD: validate-order\n"));
        Assert.assertTrue(text.contains("├── TAP: reserve-stock\n"));
        Assert.assertTrue(text.contains("├── CHOOSE: choose-channel\n"));
        Assert.assertTrue(text.contains("│   ├── [CARD]\n"));
        Assert.assertTrue(text.contains("│   │   └── TAP: call-card-gateway\n"));
        Assert.assertTrue(text.contains("│   ├── [WALLET]\n"));
        Assert.assertTrue(text.contains("│   │   └── TAP: call-wallet-gateway\n"));
        Assert.assertTrue(text.contains("│   └── [otherwise -> STOPPED]\n"));
        Assert.assertTrue(text.contains("├── SUBFLOW: sub-payment\n"));
        Assert.assertTrue(text.contains("│   └── STEP: pay-step\n"));
        Assert.assertTrue(text.contains("├── STEP: build-receipt\n"));
        Assert.assertTrue(text.contains("├── RECOVER: fallback\n"));
        Assert.assertTrue(text.contains("└── ENSURE: cleanup-metrics\n"));
    }

    @Test
    public void text_rendersOtherwiseBranch() {
        Flow<String, String> flow = Flows.<String>begin("otherwise-flow")
                .choose("decision", in -> in)
                    .when("OPT_1", Flows.step("step-1", in -> in))
                    .otherwise(Flows.step("step-default", in -> in))
                .end()
                .build();

        String text = FlowGraphs.text().render(flow.describe());
        Assert.assertTrue(text.contains("[OPT_1]"));
        Assert.assertTrue(text.contains("└── [otherwise]\n"));
        Assert.assertTrue(text.contains("STEP: step-default"));
    }

    @Test
    public void text_rendersEmptyFlow() {
        FlowDescription desc = new FlowDescription("empty-flow", null);
        String text = FlowGraphs.text().render(desc);
        Assert.assertEquals("Flow: empty-flow\n", text);
    }

    private static String findProcessNodeId(String mermaid, String label) {
        Pattern pattern = Pattern.compile("(node_\\d+_[^\\[\\s]+)\\[\\\""
                + Pattern.quote(label) + "\\\"\\]");
        Matcher matcher = pattern.matcher(mermaid);
        Assert.assertTrue("Missing Mermaid node: " + label + "\n" + mermaid, matcher.find());
        return matcher.group(1);
    }

    private static String findEndId(String mermaid) {
        Pattern pattern = Pattern.compile("(end_\\d+)\\(");
        Matcher matcher = pattern.matcher(mermaid);
        Assert.assertTrue("Missing Mermaid End node\n" + mermaid, matcher.find());
        return matcher.group(1);
    }

    private static boolean pathExists(String mermaid, String source, String target) {
        Pattern edgePattern = Pattern.compile(
                "^\\s*([a-zA-Z0-9_]+)\\s+(?:-->|-\\.->)(?:\\|[^|]*\\|)?\\s+([a-zA-Z0-9_]+)\\s*$");
        Map<String, Set<String>> graph = new HashMap<>();
        for (String line : mermaid.split("\\n")) {
            Matcher matcher = edgePattern.matcher(line);
            if (matcher.matches()) {
                Set<String> targets = graph.get(matcher.group(1));
                if (targets == null) {
                    targets = new HashSet<>();
                    graph.put(matcher.group(1), targets);
                }
                targets.add(matcher.group(2));
            }
        }

        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(source);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (target.equals(current)) {
                return true;
            }
            Set<String> next = graph.get(current);
            if (next != null) {
                pending.addAll(next);
            }
        }
        return false;
    }
}
