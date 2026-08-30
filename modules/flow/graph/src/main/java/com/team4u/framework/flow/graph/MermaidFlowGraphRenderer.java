package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.FlowDescription;
import com.team4u.framework.flow.NodeDescription;
import com.team4u.framework.flow.NodeKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mermaid format flow graph renderer.
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
        sb.append("    ").append(startId).append("([\"Start: ")
                .append(escapeLabel(description.flowId())).append("\"])\n");

        ScopeExits rootExits = renderScope(sb, description.nodes(),
                Collections.singletonList(startId), null, idSeq, "    ");

        String endId = "end_" + idSeq.incrementAndGet();
        sb.append("    ").append(endId).append("([\"End: ")
                .append(escapeLabel(description.flowId())).append("\"])\n");
        for (String exitId : rootExits.successExits) {
            sb.append("    ").append(exitId).append(" --> ").append(endId).append("\n");
        }

        if (!rootExits.stoppedExits.isEmpty()) {
            String stoppedId = "terminal_stopped_" + idSeq.incrementAndGet();
            sb.append("    ").append(stoppedId).append("([\"STOPPED\"])\n");
            sb.append("    style ").append(stoppedId).append(" fill:#f9f,stroke:#333\n");
            for (String exitId : rootExits.stoppedExits) {
                sb.append("    ").append(exitId).append(" -->|stopped| ")
                        .append(stoppedId).append("\n");
            }
        }

        if (!rootExits.failureExits.isEmpty()) {
            String failedId = "terminal_failed_" + idSeq.incrementAndGet();
            sb.append("    ").append(failedId).append("([\"FAILED\"])\n");
            sb.append("    style ").append(failedId).append(" fill:#fee2e2,stroke:#991b1b\n");
            for (String exitId : rootExits.failureExits) {
                sb.append("    ").append(exitId).append(" -.->|failed| ")
                        .append(failedId).append("\n");
            }
        }

        return sb.toString();
    }

    private static final class ScopeExits {
        private final List<String> successExits;
        private final List<String> failureExits;
        private final List<String> stoppedExits;

        private ScopeExits(List<String> successExits,
                           List<String> failureExits,
                           List<String> stoppedExits) {
            this.successExits = immutableDistinct(successExits);
            this.failureExits = immutableDistinct(failureExits);
            this.stoppedExits = immutableDistinct(stoppedExits);
        }

        private static ScopeExits empty(List<String> entryNodes) {
            return new ScopeExits(entryNodes, Collections.emptyList(), Collections.emptyList());
        }
    }

    private ScopeExits renderScope(StringBuilder sb,
                                   List<NodeDescription> nodes,
                                   List<String> entryNodes,
                                   String incomingEdgeLabel,
                                   AtomicInteger idSeq,
                                   String indent) {
        if (nodes == null || nodes.isEmpty()) {
            return ScopeExits.empty(entryNodes);
        }

        List<NodeDescription> bodyNodes = new ArrayList<>();
        NodeDescription recoverNode = null;
        NodeDescription ensureNode = null;
        for (NodeDescription node : nodes) {
            if (node.kind() == NodeKind.RECOVER) {
                recoverNode = node;
            } else if (node.kind() == NodeKind.ENSURE) {
                ensureNode = node;
            } else {
                bodyNodes.add(node);
            }
        }

        List<String> successExits = distinct(entryNodes);
        List<String> failureExits = new ArrayList<>();
        List<String> stoppedExits = new ArrayList<>();

        for (int i = 0; i < bodyNodes.size(); i++) {
            String edgeLabel = i == 0 ? incomingEdgeLabel : null;
            ScopeExits nodeExits = renderNode(sb, bodyNodes.get(i), successExits,
                    edgeLabel, idSeq, indent);
            successExits = nodeExits.successExits;
            failureExits.addAll(nodeExits.failureExits);
            stoppedExits.addAll(nodeExits.stoppedExits);
        }

        failureExits = distinct(failureExits);
        stoppedExits = distinct(stoppedExits);

        if (recoverNode != null && !failureExits.isEmpty()) {
            ScopeExits recovered = renderRecover(sb, recoverNode, failureExits, idSeq, indent);
            List<String> mergedSuccess = new ArrayList<>(successExits);
            mergedSuccess.addAll(recovered.successExits);
            successExits = distinct(mergedSuccess);

            List<String> mergedStopped = new ArrayList<>(stoppedExits);
            mergedStopped.addAll(recovered.stoppedExits);
            stoppedExits = distinct(mergedStopped);
            failureExits = recovered.failureExits;
        }

        if (ensureNode != null) {
            return renderEnsure(sb, ensureNode, successExits, stoppedExits,
                    failureExits, idSeq, indent);
        }

        return new ScopeExits(successExits, failureExits, stoppedExits);
    }

    private ScopeExits renderRecover(StringBuilder sb,
                                     NodeDescription recoverNode,
                                     List<String> failureInputs,
                                     AtomicInteger idSeq,
                                     String indent) {
        String recoverId = makeNodeId(recoverNode, idSeq);
        sb.append(indent).append(recoverId).append("[\"Recover: ")
                .append(escapeLabel(recoverNode.id())).append("\"]\n");
        for (String failureInput : failureInputs) {
            sb.append(indent).append(failureInput).append(" -.->|on failure| ")
                    .append(recoverId).append("\n");
        }

        String successId = makeGatewayId("recover_success", idSeq);
        String stoppedId = makeGatewayId("recover_stopped", idSeq);
        String failedId = makeGatewayId("recover_failed", idSeq);
        appendGateway(sb, successId, indent);
        appendGateway(sb, stoppedId, indent);
        appendGateway(sb, failedId, indent);
        sb.append(indent).append(recoverId).append(" -->|recovered| ")
                .append(successId).append("\n");
        sb.append(indent).append(recoverId).append(" -->|stopped| ")
                .append(stoppedId).append("\n");
        sb.append(indent).append(recoverId).append(" -.->|failed| ")
                .append(failedId).append("\n");

        return new ScopeExits(Collections.singletonList(successId),
                Collections.singletonList(failedId), Collections.singletonList(stoppedId));
    }

    private ScopeExits renderEnsure(StringBuilder sb,
                                    NodeDescription ensureNode,
                                    List<String> successInputs,
                                    List<String> stoppedInputs,
                                    List<String> failureInputs,
                                    AtomicInteger idSeq,
                                    String indent) {
        List<String> successExits = new ArrayList<>();
        List<String> stoppedExits = new ArrayList<>();
        List<String> failureExits = new ArrayList<>();

        if (!successInputs.isEmpty()) {
            String ensureId = appendEnsureNode(sb, ensureNode, null, idSeq, indent);
            connectOutcomeInputs(sb, successInputs, ensureId, false, "success", indent);

            String completedId = makeGatewayId("ensure_success", idSeq);
            String failedId = makeGatewayId("ensure_failed", idSeq);
            appendGateway(sb, completedId, indent);
            appendGateway(sb, failedId, indent);
            sb.append(indent).append(ensureId).append(" -->|completed| ")
                    .append(completedId).append("\n");
            sb.append(indent).append(ensureId).append(" -.->|failed| ")
                    .append(failedId).append("\n");
            successExits.add(completedId);
            failureExits.add(failedId);
        }

        if (!stoppedInputs.isEmpty()) {
            String ensureId = appendEnsureNode(sb, ensureNode, "stopped", idSeq, indent);
            connectOutcomeInputs(sb, stoppedInputs, ensureId, false, "stopped", indent);

            String preservedId = makeGatewayId("ensure_stopped", idSeq);
            String failedId = makeGatewayId("ensure_failed", idSeq);
            appendGateway(sb, preservedId, indent);
            appendGateway(sb, failedId, indent);
            sb.append(indent).append(ensureId).append(" -->|stop preserved| ")
                    .append(preservedId).append("\n");
            sb.append(indent).append(ensureId).append(" -.->|failed| ")
                    .append(failedId).append("\n");
            stoppedExits.add(preservedId);
            failureExits.add(failedId);
        }

        if (!failureInputs.isEmpty()) {
            String ensureId = appendEnsureNode(sb, ensureNode, "failed", idSeq, indent);
            connectOutcomeInputs(sb, failureInputs, ensureId, true, "failed", indent);

            String preservedId = makeGatewayId("ensure_failure", idSeq);
            appendGateway(sb, preservedId, indent);
            sb.append(indent).append(ensureId).append(" -.->|failure preserved| ")
                    .append(preservedId).append("\n");
            failureExits.add(preservedId);
        }

        return new ScopeExits(successExits, failureExits, stoppedExits);
    }

    private ScopeExits renderNode(StringBuilder sb,
                                  NodeDescription node,
                                  List<String> entryNodes,
                                  String incomingEdgeLabel,
                                  AtomicInteger idSeq,
                                  String indent) {
        String nodeId = makeNodeId(node, idSeq);

        switch (node.kind()) {
            case STEP:
                appendProcessNode(sb, nodeId, "Step: " + node.id(), indent);
                connectEntries(sb, entryNodes, incomingEdgeLabel, nodeId, indent);
                return new ScopeExits(Collections.singletonList(nodeId),
                        Collections.singletonList(nodeId), Collections.emptyList());

            case TAP:
                appendProcessNode(sb, nodeId, "Tap: " + node.id(), indent);
                connectEntries(sb, entryNodes, incomingEdgeLabel, nodeId, indent);
                return new ScopeExits(Collections.singletonList(nodeId),
                        Collections.singletonList(nodeId), Collections.emptyList());

            case GUARD:
                sb.append(indent).append(nodeId).append("{\"Guard: ")
                        .append(escapeLabel(node.id())).append("\"}\n");
                connectEntries(sb, entryNodes, incomingEdgeLabel, nodeId, indent);

                String stopId = "stop_" + idSeq.incrementAndGet();
                sb.append(indent).append(stopId).append("([\"STOPPED\"])\n");
                sb.append(indent).append("style ").append(stopId)
                        .append(" fill:#f9f,stroke:#333\n");
                sb.append(indent).append(nodeId).append(" -->|stopped| ")
                        .append(stopId).append("\n");

                String passId = makeGatewayId("guard_pass", idSeq);
                appendGateway(sb, passId, indent);
                sb.append(indent).append(nodeId).append(" -->|passed| ")
                        .append(passId).append("\n");
                return new ScopeExits(Collections.singletonList(passId),
                        Collections.singletonList(nodeId), Collections.singletonList(stopId));

            case CHOOSE:
                return renderChoose(sb, node, nodeId, entryNodes,
                        incomingEdgeLabel, idSeq, indent);

            case SUBFLOW:
                String subgraphId = "sub_" + nodeId;
                sb.append(indent).append("subgraph ").append(subgraphId)
                        .append(" [\"Subflow: ").append(escapeLabel(node.id())).append("\"]\n");
                FlowDescription subflow = node.subflow();
                List<NodeDescription> subNodes = subflow != null
                        ? subflow.nodes() : Collections.emptyList();
                ScopeExits subExits = renderScope(sb, subNodes, entryNodes,
                        incomingEdgeLabel, idSeq, indent + "    ");
                sb.append(indent).append("end\n");
                return subExits;

            case SEQUENCE:
                return renderScope(sb, node.children(), entryNodes,
                        incomingEdgeLabel, idSeq, indent);

            default:
                appendProcessNode(sb, nodeId, node.id(), indent);
                connectEntries(sb, entryNodes, incomingEdgeLabel, nodeId, indent);
                return new ScopeExits(Collections.singletonList(nodeId),
                        Collections.singletonList(nodeId), Collections.emptyList());
        }
    }

    private ScopeExits renderChoose(StringBuilder sb,
                                    NodeDescription node,
                                    String chooseId,
                                    List<String> entryNodes,
                                    String incomingEdgeLabel,
                                    AtomicInteger idSeq,
                                    String indent) {
        sb.append(indent).append(chooseId).append("{\"Choose: ")
                .append(escapeLabel(node.id())).append("\"}\n");
        connectEntries(sb, entryNodes, incomingEdgeLabel, chooseId, indent);

        String joinId = makeGatewayId("join", idSeq);
        appendGateway(sb, joinId, indent);
        boolean hasSuccess = false;
        List<String> failureExits = new ArrayList<>();
        failureExits.add(chooseId);
        List<String> stoppedExits = new ArrayList<>();

        if (node.branches() != null) {
            for (Map.Entry<String, FlowDescription> branch : node.branches().entrySet()) {
                ScopeExits branchExits = renderScope(sb,
                        branch.getValue() != null ? branch.getValue().nodes() : Collections.emptyList(),
                        Collections.singletonList(chooseId),
                        "\"" + escapeEdgeLabel(branch.getKey()) + "\"", idSeq, indent);
                for (String successId : branchExits.successExits) {
                    sb.append(indent).append(successId).append(" --> ")
                            .append(joinId).append("\n");
                    hasSuccess = true;
                }
                failureExits.addAll(branchExits.failureExits);
                stoppedExits.addAll(branchExits.stoppedExits);
            }
        }

        if (node.otherwiseBranch() != null) {
            ScopeExits otherwiseExits = renderScope(sb, node.otherwiseBranch().nodes(),
                    Collections.singletonList(chooseId), "otherwise", idSeq, indent);
            for (String successId : otherwiseExits.successExits) {
                sb.append(indent).append(successId).append(" --> ")
                        .append(joinId).append("\n");
                hasSuccess = true;
            }
            failureExits.addAll(otherwiseExits.failureExits);
            stoppedExits.addAll(otherwiseExits.stoppedExits);
        } else if (node.hasOtherwiseStop()) {
            String stopId = "stop_" + idSeq.incrementAndGet();
            sb.append(indent).append(stopId).append("([\"STOPPED\"])\n");
            sb.append(indent).append("style ").append(stopId)
                    .append(" fill:#f9f,stroke:#333\n");
            sb.append(indent).append(chooseId).append(" -->|otherwise| ")
                    .append(stopId).append("\n");
            stoppedExits.add(stopId);
        }

        return new ScopeExits(hasSuccess ? Collections.singletonList(joinId) : Collections.emptyList(),
                failureExits, stoppedExits);
    }

    private static String appendEnsureNode(StringBuilder sb,
                                           NodeDescription ensureNode,
                                           String outcome,
                                           AtomicInteger idSeq,
                                           String indent) {
        String ensureId = makeNodeId(ensureNode, idSeq);
        String suffix = outcome == null ? "" : " [" + outcome + "]";
        appendProcessNode(sb, ensureId, "Ensure: " + ensureNode.id() + suffix, indent);
        return ensureId;
    }

    private static void appendProcessNode(StringBuilder sb,
                                          String nodeId,
                                          String label,
                                          String indent) {
        sb.append(indent).append(nodeId).append("[\"")
                .append(escapeLabel(label)).append("\"]\n");
    }

    private static void appendGateway(StringBuilder sb, String gatewayId, String indent) {
        sb.append(indent).append(gatewayId).append("(( ))\n");
    }

    private static void connectOutcomeInputs(StringBuilder sb,
                                             List<String> inputs,
                                             String targetId,
                                             boolean failureEdge,
                                             String edgeLabel,
                                             String indent) {
        for (String input : distinct(inputs)) {
            sb.append(indent).append(input)
                    .append(failureEdge ? " -.->|" : " -->|")
                    .append(edgeLabel).append("| ").append(targetId).append("\n");
        }
    }

    private static void connectEntries(StringBuilder sb,
                                       List<String> entryNodes,
                                       String edgeLabel,
                                       String targetId,
                                       String indent) {
        for (String entry : distinct(entryNodes)) {
            sb.append(indent).append(entry).append(" -->");
            if (edgeLabel != null && !edgeLabel.isEmpty()) {
                sb.append("|").append(edgeLabel).append("|");
            }
            sb.append(" ").append(targetId).append("\n");
        }
    }

    private static String makeNodeId(NodeDescription node, AtomicInteger idSeq) {
        int sequence = idSeq.incrementAndGet();
        String address = node.address();
        String source = address != null && !address.isEmpty() && !"/".equals(address)
                ? address : node.id();
        return "node_" + sequence + "_" + sanitize(source);
    }

    private static String makeGatewayId(String prefix, AtomicInteger idSeq) {
        return prefix + "_" + idSeq.incrementAndGet();
    }

    private static List<String> immutableDistinct(List<String> values) {
        return Collections.unmodifiableList(distinct(values));
    }

    private static List<String> distinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>(values);
        return new ArrayList<>(unique);
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "node";
        }
        String sanitized = value.replaceAll("[^a-zA-Z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return sanitized.isEmpty() ? "node" : sanitized;
    }

    private static String escapeLabel(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "#92;")
                .replace("\"", "#quot;")
                .replace("\r\n", "<br/>")
                .replace("\n", "<br/>")
                .replace("\r", "");
    }

    private static String escapeEdgeLabel(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "#92;")
                .replace("\"", "#quot;")
                .replace("|", "#124;")
                .replace("\r\n", "<br/>")
                .replace("\n", "<br/>")
                .replace("\r", "");
    }
}
