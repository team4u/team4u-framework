package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.FlowDescription;
import com.team4u.framework.flow.NodeDescription;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文本树形格式流程图渲染器。
 *
 * @author jay.wu
 */
final class TextFlowGraphRenderer implements FlowGraphRenderer {

    static final TextFlowGraphRenderer INSTANCE = new TextFlowGraphRenderer();

    @Override
    public String render(FlowDescription description) {
        Objects.requireNonNull(description, "FlowDescription must not be null");

        StringBuilder sb = new StringBuilder();
        sb.append("Flow: ").append(description.flowId()).append("\n");
        renderNodes(sb, description.nodes(), "");
        return sb.toString();
    }

    private void renderNodes(StringBuilder sb, List<NodeDescription> nodes, String prefix) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            boolean isLast = (i == nodes.size() - 1);
            NodeDescription node = nodes.get(i);
            String branchConnector = isLast ? "└── " : "├── ";
            String childPrefix = prefix + (isLast ? "    " : "│   ");

            sb.append(prefix).append(branchConnector).append(node.kind()).append(": ").append(node.id()).append("\n");

            boolean hasBranches = node.branches() != null && !node.branches().isEmpty();
            boolean hasOtherwiseBranch = node.otherwiseBranch() != null;
            boolean hasOtherwiseStop = node.hasOtherwiseStop();

            if (hasBranches || hasOtherwiseBranch || hasOtherwiseStop) {
                int bTotal = (node.branches() != null ? node.branches().size() : 0) + (hasOtherwiseBranch || hasOtherwiseStop ? 1 : 0);
                int bIndex = 0;
                if (hasBranches) {
                    for (Map.Entry<String, FlowDescription> entry : node.branches().entrySet()) {
                        boolean bLast = (++bIndex == bTotal);
                        String bConn = bLast ? "└── " : "├── ";
                        String bChildPrefix = childPrefix + (bLast ? "    " : "│   ");
                        sb.append(childPrefix).append(bConn).append("[").append(entry.getKey()).append("]\n");
                        if (entry.getValue() != null) {
                            renderNodes(sb, entry.getValue().nodes(), bChildPrefix);
                        }
                    }
                }
                if (hasOtherwiseBranch) {
                    sb.append(childPrefix).append("└── [otherwise]\n");
                    renderNodes(sb, node.otherwiseBranch().nodes(), childPrefix + "    ");
                } else if (hasOtherwiseStop) {
                    sb.append(childPrefix).append("└── [otherwise -> STOPPED]\n");
                }
            }

            if (node.subflow() != null) {
                renderNodes(sb, node.subflow().nodes(), childPrefix);
            }

            if (node.children() != null && !node.children().isEmpty()) {
                renderNodes(sb, node.children(), childPrefix);
            }
        }
    }
}
