package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.FlowDescription;
import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.Flows;
import com.team4u.framework.flow.StopReason;
import org.junit.Assert;
import org.junit.Test;

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
    }

    @Test
    public void mermaid_escapesSpecialCharacters() {
        Flow<String, String> flow = Flows.<String>begin("test\"flow<special>")
                .step("step\"with[quotes]&brackets", in -> in)
                .build();

        FlowDescription desc = flow.describe();
        String mermaid = FlowGraphs.mermaid().render(desc);

        Assert.assertFalse(mermaid.contains("\"step\"with")); // Double quote inside label escaped
        Assert.assertTrue(mermaid.contains("#quot;"));
    }

    @Test
    public void text_rendersTreeStructure() {
        Flow<String, String> flow = Flows.<String>begin("tree-flow")
                .step("step-1", in -> in)
                .tap("tap-1", in -> {})
                .choose("ch-1", in -> in)
                    .when("A", Flows.step("step-a", in -> in))
                    .when("B", Flows.step("step-b", in -> in))
                .end()
                .build();

        FlowDescription desc = flow.describe();
        String text = FlowGraphs.text().render(desc);

        Assert.assertNotNull(text);
        Assert.assertTrue(text.contains("Flow: tree-flow"));
        Assert.assertTrue(text.contains("STEP: step-1"));
        Assert.assertTrue(text.contains("TAP: tap-1"));
        Assert.assertTrue(text.contains("CHOOSE: ch-1"));
        Assert.assertTrue(text.contains("[A]"));
        Assert.assertTrue(text.contains("[B]"));
    }
}
