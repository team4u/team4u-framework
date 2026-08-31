package com.team4u.framework.flow.diagram;

import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.desc.NodeDescription;
import com.team4u.framework.flow.spi.BindingDescriptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 紧凑型确定性文本树流程图渲染器（Compact Deterministic Text Flow Diagram Renderer）。
 *
 * <p>基于 {@link FlowDescription} 静态拓扑描述模型，按先序遍历逐行输出紧凑、结构化的纯文本节点与属性信息，便于日志打印、断言比对与纯命令行环境排查。</p>
 *
 * @author jay.wu
 */
final class TextFlowDiagramRenderer implements FlowDiagramRenderer {

    static final TextFlowDiagramRenderer INSTANCE = new TextFlowDiagramRenderer();

    @Override
    public String render(FlowDescription description) {
        Objects.requireNonNull(description, "FlowDescription must not be null");

        StringBuilder output = new StringBuilder();
        output.append("flow id=\"").append(FlowDiagramFormatters.escapeText(FlowDiagramFormatters.display(description.flowId())))
                .append("\"\n");
        Deque<NodeDescription> pending = new ArrayDeque<NodeDescription>();
        pending.addLast(description.root());
        while (!pending.isEmpty()) {
            NodeDescription node = pending.removeLast();
            appendNode(output, node);
            List<NodeDescription> children = node.children();
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.addLast(children.get(index));
            }
        }
        return output.toString();
    }

    private static void appendNode(StringBuilder output, NodeDescription node) {
        output.append("path=\"").append(FlowDiagramFormatters.escapeText(node.path())).append("\"")
                .append(" kind=").append(node.kind().name())
        .append(" label=");
        if (node.label().isPresent()) {
            output.append("\"").append(FlowDiagramFormatters.escapeText(node.label().get())).append("\"");
        } else {
            output.append("<none>");
        }

        if (node.binding().isPresent()) {
            appendBinding(output, node.binding().get());
        }
        switch (node.kind()) {
            case SEQUENCE:
                output.append(" scope=");
                quotedOrNone(output, node.scopeName());
                output.append(" children=").append(node.children().size());
                break;
            case ROUTE:
                output.append(" routes=").append(node.routeCases().size())
                        .append(" otherwise=")
                        .append(node.otherwise() == null ? "no-match:SKIPPED" : "branch");
                break;
            case FALLBACK:
                output.append(" trigger=").append(FlowDiagramFormatters.display(node.trigger()))
                        .append(" branches=").append(node.children().size());
                break;
            case PARALLEL:
                output.append(" branches=").append(node.parallelBranches().size())
                        .append(" tokens=[");
                for (int index = 0; index < node.parallelBranches().size(); index++) {
                    if (index > 0) output.append(',');
                    output.append('"').append(FlowDiagramFormatters.escapeText(node.parallelBranches().get(index).name()))
                            .append('"');
                }
                output.append("] join=static");
                break;
            case AWAIT:
                output.append(" resume=\"").append(FlowDiagramFormatters.escapeText(FlowDiagramFormatters.display(node.resumePoint())))
                        .append('"');
                break;
            case CONTROL:
                output.append(" control=").append(FlowDiagramFormatters.display(node.controlKind()))
                        .append(" config=").append(FlowDiagramFormatters.configurationSummary(node.configuration()));
                break;
            case COMPLETE:
                output.append(" complete=").append(node.identity()
                        ? "IDENTITY" : node.outcome().kind().name());
                break;
            case INVOKE:
                break;
            default:
                throw new IllegalStateException("Unknown node kind: " + node.kind());
        }
        output.append('\n');
    }

    private static void appendBinding(StringBuilder output, BindingDescriptor binding) {
        output.append(" binding=").append(FlowDiagramFormatters.escapeText(binding.kind()))
                .append(" contract=");
        if (binding.contractClass().isPresent()) {
            output.append(FlowDiagramFormatters.escapeText(binding.contractClass().get().getName()));
        } else {
            output.append("<unresolved>");
        }
        output.append(" qualifier=");
        if (binding.qualifier().isPresent()) {
            output.append('"').append(FlowDiagramFormatters.escapeText(binding.qualifier().get())).append('"');
        } else {
            output.append("<none>");
        }
    }

    private static void quotedOrNone(StringBuilder output, String value) {
        if (value == null) {
            output.append("<none>");
        } else {
            output.append('"').append(FlowDiagramFormatters.escapeText(value)).append('"');
        }
    }
}
