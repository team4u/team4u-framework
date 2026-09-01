package com.team4u.framework.flow.log;

import java.util.List;

/**
 * 流程执行链路 ASCII 树状结构格式化器。
 *
 * @author jay.wu
 */
public final class TraceTreeFormatter {

    private TraceTreeFormatter() {
    }

    /**
     * 将流程执行树节点格式化为清晰的 ASCII 目录树文本。
     *
     * @param root 根节点
     * @return 格式化后的树形文本字符串
     */
    public static String formatTree(TraceNode root) {
        if (root == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        renderNode(root, "", true, sb);
        return sb.toString();
    }

    private static void renderNode(TraceNode node, String prefix, boolean isTail, StringBuilder sb) {
        sb.append(prefix).append(isTail ? "└── " : "├── ")
                .append("[").append(node.getPath()).append("] ")
                .append(node.getLabel() != null && !node.getLabel().isEmpty() ? node.getLabel() : "<unnamed>")
                .append(" (").append(node.getDurationMs()).append("ms)")
                .append(" [").append(node.getOutcome()).append("]");

        if (node.getExtra() != null && !node.getExtra().trim().isEmpty()) {
            sb.append(" ").append(node.getExtra().trim());
        }
        sb.append("\n");

        List<TraceNode> children = node.snapshotChildren();
        String childPrefix = prefix + (isTail ? "    " : "│   ");
        for (int i = 0; i < children.size(); i++) {
            boolean childTail = (i == children.size() - 1);
            renderNode(children.get(i), childPrefix, childTail, sb);
        }
    }
}
