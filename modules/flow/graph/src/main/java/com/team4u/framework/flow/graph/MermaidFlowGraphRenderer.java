package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.FlowDescription;
import com.team4u.framework.flow.NodeDescription;
import com.team4u.framework.flow.NodeKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mermaid 格式流程图渲染器。
 *
 * @author jay.wu
 */
final class MermaidFlowGraphRenderer implements FlowGraphRenderer {

    static final MermaidFlowGraphRenderer INSTANCE = new MermaidFlowGraphRenderer();

    @Override
    public String render(FlowDescription description) {
        Objects.requireNonNull(description, "FlowDescription must not be null");

        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");

        AtomicInteger idSeq = new AtomicInteger();
        String startId = "start_" + idSeq.incrementAndGet();
        sb.append("    ").append(startId).append("([\"Start: ").append(escape(description.flowId())).append("\"])\n");

        String currentPrev = startId;
        List<NodeDescription> nodes = description.nodes();

        NodeDescription recoverNode = null;
        NodeDescription ensureNode = null;
        List<NodeDescription> bodyNodes = new ArrayList<>();

        for (NodeDescription node : nodes) {
            if (node.kind() == NodeKind.RECOVER) {
                recoverNode = node;
            } else if (node.kind() == NodeKind.ENSURE) {
                ensureNode = node;
            } else {
                bodyNodes.add(node);
            }
        }

        List<String> exitNodes = new ArrayList<>();
        currentPrev = renderNodeList(sb, bodyNodes, currentPrev, idSeq, exitNodes);

        if (recoverNode != null) {
            String recId = "node_" + idSeq.incrementAndGet() + "_" + sanitize(recoverNode.id());
            sb.append("    ").append(recId).append("[\"Recover: ").append(escape(recoverNode.id())).append("\"]\n");
            sb.append("    ").append(currentPrev).append(" -.->|on failure| ").append(recId).append("\n");
            currentPrev = recId;
        }

        if (ensureNode != null) {
            String ensId = "node_" + idSeq.incrementAndGet() + "_" + sanitize(ensureNode.id());
            sb.append("    ").append(ensId).append("[\"Ensure: ").append(escape(ensureNode.id())).append("\"]\n");
            sb.append("    ").append(currentPrev).append(" --> ").append(ensId).append("\n");
            currentPrev = ensId;
        }

        String endId = "end_" + idSeq.incrementAndGet();
        sb.append("    ").append(endId).append("([\"End: ").append(escape(description.flowId())).append("\"])\n");
        sb.append("    ").append(currentPrev).append(" --> ").append(endId).append("\n");

        return sb.toString();
    }

    private String renderNodeList(StringBuilder sb, List<NodeDescription> nodes, String prev,
                                  AtomicInteger idSeq, List<String> exitNodes) {
        String current = prev;
        for (NodeDescription node : nodes) {
            current = renderNode(sb, node, current, idSeq);
        }
        return current;
    }

    private String renderNode(StringBuilder sb, NodeDescription node, String prev, AtomicInteger idSeq) {
        String nId = "node_" + idSeq.incrementAndGet() + "_" + sanitize(node.id());

        switch (node.kind()) {
            case STEP:
                sb.append("    ").append(nId).append("[\"Step: ").append(escape(node.id())).append("\"]\n");
                sb.append("    ").append(prev).append(" --> ").append(nId).append("\n");
                return nId;

            case TAP:
                sb.append("    ").append(nId).append("[\"Tap: ").append(escape(node.id())).append("\"]\n");
                sb.append("    ").append(prev).append(" --> ").append(nId).append("\n");
                return nId;

            case GUARD:
                sb.append("    ").append(nId).append("{\"Guard: ").append(escape(node.id())).append("\"}\n");
                sb.append("    ").append(prev).append(" --> ").append(nId).append("\n");
                String stopId = "stop_" + idSeq.incrementAndGet();
                sb.append("    ").append(stopId).append("([\"STOPPED\"]).style fill:#f9f,stroke:#333\n");
                sb.append("    ").append(nId).append(" -->|stopped| ").append(stopId).append("\n");
                String passNext = "guard_pass_" + idSeq.incrementAndGet();
                sb.append("    ").append(nId).append(" -->|passed| ").append(passNext).append("(( ))\n");
                return passNext;

            case CHOOSE:
                sb.append("    ").append(nId).append("{\"Choose: ").append(escape(node.id())).append("\"}\n");
                sb.append("    ").append(prev).append(" --> ").append(nId).append("\n");

                String joinId = "join_" + idSeq.incrementAndGet() + "(( ))";
                String joinTarget = "join_" + idSeq.get();
                sb.append("    ").append(joinId).append("\n");

                for (Map.Entry<String, FlowDescription> entry : node.branches().entrySet()) {
                    String branchKey = entry.getKey();
                    FlowDescription branchFlow = entry.getValue();
                    String branchEntry = renderSubflowDirect(sb, branchFlow, idSeq, joinTarget);
                    sb.append("    ").append(nId).append(" -->|\"").append(escape(branchKey)).append("\"| ").append(branchEntry).append("\n");
                }

                if (node.otherwiseBranch() != null) {
                    String otherwiseEntry = renderSubflowDirect(sb, node.otherwiseBranch(), idSeq, joinTarget);
                    sb.append("    ").append(nId).append(" -->|otherwise| ").append(otherwiseEntry).append("\n");
                } else if (node.hasOtherwiseStop()) {
                    String stopOtherId = "stop_" + idSeq.incrementAndGet();
                    sb.append("    ").append(stopOtherId).append("([\"STOPPED\"]).style fill:#f9f,stroke:#333\n");
                    sb.append("    ").append(nId).append(" -->|otherwise| ").append(stopOtherId).append("\n");
                }

                return joinTarget;

            case SUBFLOW:
                sb.append("    subgraph sub_").append(nId).append(" [\"Subflow: ").append(escape(node.id())).append("\"]\n");
                if (node.subflow() != null) {
                    renderSubflowBody(sb, node.subflow(), nId, idSeq);
                }
                sb.append("    end\n");
                sb.append("    ").append(prev).append(" --> sub_").append(nId).append("\n");
                return "sub_" + nId;

            default:
                sb.append("    ").append(nId).append("[\"").append(escape(node.id())).append("\"]\n");
                sb.append("    ").append(prev).append(" --> ").append(nId).append("\n");
                return nId;
        }
    }

    private String renderSubflowDirect(StringBuilder sb, FlowDescription flow, AtomicInteger idSeq, String joinTarget) {
        List<NodeDescription> nodes = flow.nodes();
        if (nodes.isEmpty()) {
            return joinTarget;
        }
        String firstId = null;
        String prev = null;
        for (int i = 0; i < nodes.size(); i++) {
            NodeDescription nd = nodes.get(i);
            String currId = "node_" + idSeq.incrementAndGet() + "_" + sanitize(nd.id());
            sb.append("    ").append(currId).append("[\"").append(escape(nd.id())).append("\"]\n");
            if (i == 0) {
                firstId = currId;
            } else {
                sb.append("    ").append(prev).append(" --> ").append(currId).append("\n");
            }
            prev = currId;
        }
        sb.append("    ").append(prev).append(" --> ").append(joinTarget).append("\n");
        return firstId;
    }

    private void renderSubflowBody(StringBuilder sb, FlowDescription flow, String subPrefix, AtomicInteger idSeq) {
        String prev = null;
        for (NodeDescription nd : flow.nodes()) {
            String currId = "sub_" + idSeq.incrementAndGet() + "_" + sanitize(nd.id());
            sb.append("        ").append(currId).append("[\"").append(escape(nd.id())).append("\"]\n");
            if (prev != null) {
                sb.append("        ").append(prev).append(" --> ").append(currId).append("\n");
            }
            prev = currId;
        }
    }

    private static String sanitize(String str) {
        if (str == null) return "node";
        return str.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String escape(String str) {
        if (str == null) return "";
        return str.replace("\"", "#quot;").replace("\n", " ").replace("\r", "");
    }
}
