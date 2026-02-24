package com.team4u.criterion.trace;

import com.team4u.criterion.model.LogicCriterion;
import com.team4u.criterion.model.PropertyCriterion;

/**
 * 追踪树渲染器
 * 将 TraceNode 树形结构渲染为 ASCII 艺术字符串或紧凑字符串
 */
public class TraceTreeRenderer {

    /**
     * 将追踪树渲染为紧凑的单行字符串
     * <p>
     * 格式示例：(age > 18 {20}[Y] AND role == 'admin' {"user"}[N])[N]
     *
     * @param node 根追踪追踪节点
     * @return 紧凑字符串
     */
    public String render(TraceNode node) {
        if (node == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendNode(sb, node);
        return sb.toString();
    }

    private void appendNode(StringBuilder sb, TraceNode node) {
        // 1. 逻辑节点：渲染为 (A AND B) 形式
        if (node.getCriterion() instanceof LogicCriterion) {
            sb.append("(");
            String op = ((LogicCriterion) node.getCriterion()).getOperator().name();
            for (int i = 0; i < node.getChildren().size(); i++) {
                if (i > 0) {
                    sb.append(" ").append(op).append(" ");
                }
                appendNode(sb, node.getChildren().get(i));
            }
            sb.append(")");
            appendResultMark(sb, node.isMatched());
            return;
        }

        // 2. 属性节点优化：若只有一个子节点（值比较），则合并展示，消除冗余层级
        // 效果：age > 18 {20}[Y]
        if (node.getCriterion() instanceof PropertyCriterion && node.getChildren().size() == 1) {
            TraceNode child = node.getChildren().get(0);
            sb.append(node.getDescription());
            appendValue(sb, child.getInput());
            appendResultMark(sb, node.isMatched());
            return;
        }

        // 3. 其他节点（叶子节点）：展示描述和输入值
        sb.append(node.getDescription());
        // 仅当没有子节点时展示输入值，避免重复
        if (node.getChildren().isEmpty()) {
            appendValue(sb, node.getInput());
        }
        appendResultMark(sb, node.isMatched());
    }

    private void appendValue(StringBuilder sb, Object value) {
        sb.append(" {");
        sb.append(formatValue(value));
        sb.append("}");
    }

    private void appendResultMark(StringBuilder sb, boolean result) {
        sb.append(result ? "[Y]" : "[N]");
    }

    /**
     * 简单的值格式化工具
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + value + "\"";
        }
        return String.valueOf(value);
    }
}