package com.team4u.framework.flow;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.desc.FlowVisitor;
import com.team4u.framework.flow.desc.NodeDescription;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.spi.NodeDescriptor;

/**
 * 结构化只读描述模型与 Visitor SPI 的单元测试，验证 Invoke, Sequence, Route, Fallback, Parallel, Await, Control, Complete
 * 节点的结构导出、只读性与访问者遍历。
 */
public class FlowStructureTest {

    @Test
    public void describeFlowStructureCoveringAllNodeKinds() {
        Branch<String, String> b1 = Branch.of("b1", Flow.step((c, i) -> Outcome.accepted(i + "-1")));
        Branch<String, String> b2 = Branch.of("b2", Flow.step((c, i) -> Outcome.accepted(i + "-2")));

        Flow<String, String> flow = Flow.<String, String>step((c, i) -> Outcome.accepted(i + "-step"))
                .named("my-step")
                .then(Flow.route((Operation<String, Integer>) (c, i) -> Outcome.accepted(i.length()))
                        .caseOf(5, Flow.accepted("five"))
                        .otherwise(Flow.accepted("other")))
                .then(Flow.firstApplicable(
                        Flow.skipped(Reason.of("SKIP", "skip")),
                        Flow.accepted("applicable")))
                .then(Flow.parallel(b1, b2).join(results -> results.firstAccepted().map(Object::toString)))
                .await(ResumePoint.<String>named("point1"))
                .then((c, resumed) -> Outcome.accepted(resumed.state()))
                .retry(Retry.maxAttempts(3))
                .timeout(Duration.ofSeconds(5))
                .recoverWith(Flow.step((c, recovery) -> Outcome.accepted("recovered")));

        FlowDescription desc = flow.describe("test-flow");
        assertEquals("test-flow", desc.flowId());
        NodeDescription root = desc.root();
        assertNotNull(root);

        final List<String> visitedKinds = new ArrayList<String>();
        FlowVisitor<Void> visitor = new FlowVisitor<Void>() {
            @Override public Void visitInvoke(NodeDescription node) { visitedKinds.add("INVOKE:" + node.path()); return null; }
            @Override public Void visitSequence(NodeDescription node) { visitedKinds.add("SEQUENCE:" + node.path()); return null; }
            @Override public Void visitRoute(NodeDescription node) { visitedKinds.add("ROUTE:" + node.path()); return null; }
            @Override public Void visitFallback(NodeDescription node) { visitedKinds.add("FALLBACK:" + node.path()); return null; }
            @Override public Void visitParallel(NodeDescription node) { visitedKinds.add("PARALLEL:" + node.path()); return null; }
            @Override public Void visitAwait(NodeDescription node) { visitedKinds.add("AWAIT:" + node.path()); return null; }
            @Override public Void visitControl(NodeDescription node) { visitedKinds.add("CONTROL:" + node.path()); return null; }
            @Override public Void visitComplete(NodeDescription node) { visitedKinds.add("COMPLETE:" + node.path()); return null; }
        };

        traverse(root, visitor);

        assertTrue(visitedKinds.stream().anyMatch(k -> k.startsWith("INVOKE")));
        assertTrue(visitedKinds.stream().anyMatch(k -> k.startsWith("SEQUENCE")));
        assertTrue(visitedKinds.stream().anyMatch(k -> k.startsWith("ROUTE")));
        assertTrue(visitedKinds.stream().anyMatch(k -> k.startsWith("FALLBACK")));
        assertTrue(visitedKinds.stream().anyMatch(k -> k.startsWith("PARALLEL")));
        assertTrue(visitedKinds.stream().anyMatch(k -> k.startsWith("AWAIT")));
        assertTrue(visitedKinds.stream().anyMatch(k -> k.startsWith("CONTROL")));
        assertTrue(visitedKinds.stream().anyMatch(k -> k.startsWith("COMPLETE")));
    }

    @Test
    public void describe5000NestedScopesDoesNotStackOverflow() {
        Flow<String, String> current = Flow.step((c, i) -> Outcome.accepted(i));
        for (int i = 0; i < 5000; i++) {
            current = Flow.scope("scope-" + i, current);
        }
        FlowDescription desc = current.describe("deep-flow");
        assertEquals("deep-flow", desc.flowId());
        assertNotNull(desc.root());
        assertEquals(NodeDescriptor.Kind.SEQUENCE, desc.root().kind());
    }

    private static void traverse(NodeDescription node, FlowVisitor<Void> visitor) {
        node.accept(visitor);
        if (node.children() != null) {
            for (NodeDescription child : node.children()) {
                traverse(child, visitor);
            }
        }
    }
}
