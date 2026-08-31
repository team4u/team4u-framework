package com.team4u.framework.flow.graph;

import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.desc.NodeDescription;
import com.team4u.framework.flow.desc.ParallelBranchDescription;
import com.team4u.framework.flow.desc.RouteCaseDescription;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.spi.BindingDescriptor;
import com.team4u.framework.flow.spi.NodeDescriptor;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 业务友好型确定性 Mermaid 流程图渲染器。
 *
 * <p>将 {@link FlowDescription} 静态拓扑描述模型渲染为直观、清晰、符合人类阅读直觉的 Mermaid 流程图（{@code flowchart TD}）。
 * 采用显式工作栈消除 JVM 递归开销，自动折叠底层 AST 胶水节点，将超时/策略控制作为属性标签附着在节点上。</p>
 *
 * @author jay.wu
 */
final class MermaidFlowGraphRenderer implements FlowGraphRenderer {

    static final MermaidFlowGraphRenderer INSTANCE = new MermaidFlowGraphRenderer();

    private static final class Block {
        final String entryNodeId;
        final List<String> normalExitIds;
        final List<String> allNodeIds;

        Block(String entryNodeId, List<String> normalExitIds, List<String> allNodeIds) {
            this.entryNodeId = entryNodeId;
            this.normalExitIds = normalExitIds != null ? normalExitIds : Collections.emptyList();
            this.allNodeIds = allNodeIds != null ? allNodeIds : Collections.emptyList();
        }
    }

    private static final class Work {
        final NodeDescription node;
        final boolean build;

        Work(NodeDescription node, boolean build) {
            this.node = node;
            this.build = build;
        }
    }

    private static final class NodeMeta {
        final List<String> badges = new ArrayList<String>();
        String inheritedLabel;
    }

    private static final class RenderState {
        private final StringBuilder nodes = new StringBuilder();
        private final StringBuilder subgraphs = new StringBuilder();
        private final StringBuilder edges = new StringBuilder();
        private int sequence;

        private final List<String> startEndNodes = new ArrayList<String>();
        private final List<String> actionNodes = new ArrayList<String>();
        private final List<String> routeNodes = new ArrayList<String>();
        private final List<String> parallelNodes = new ArrayList<String>();
        private final List<String> awaitNodes = new ArrayList<String>();
        private final List<String> successNodes = new ArrayList<String>();
        private final List<String> dangerNodes = new ArrayList<String>();

        private String nextId() {
            sequence++;
            return "n" + sequence;
        }

        private void emitNode(String id, String open, String close, String label) {
            nodes.append("    ").append(id).append(open).append("\"")
                    .append(label).append("\"").append(close).append("\n");
        }

        private void emitEdge(String source, String target, String label, boolean dashed) {
            edges.append("    ").append(source);
            if (dashed) {
                edges.append(" -.->");
            } else {
                edges.append(" -->");
            }
            if (label != null && !label.isEmpty()) {
                edges.append("|").append(label).append("|");
            }
            edges.append(" ").append(target).append("\n");
        }

        private void emitSubgraph(String id, String title, List<String> containedNodeIds) {
            if (containedNodeIds.isEmpty()) return;
            subgraphs.append("    subgraph ").append(id).append(" [\"").append(title).append("\"]\n");
            for (String nodeId : containedNodeIds) {
                subgraphs.append("        ").append(nodeId).append("\n");
            }
            subgraphs.append("    end\n");
        }

        private void addStartEndNode(String id) { startEndNodes.add(id); }
        private void addActionNode(String id) { actionNodes.add(id); }
        private void addRouteNode(String id) { routeNodes.add(id); }
        private void addParallelNode(String id) { parallelNodes.add(id); }
        private void addAwaitNode(String id) { awaitNodes.add(id); }
        private void addSuccessNode(String id) { successNodes.add(id); }
        private void addDangerNode(String id) { dangerNodes.add(id); }

        private String build() {
            StringBuilder full = new StringBuilder();
            full.append("flowchart TD\n");
            full.append(nodes);
            if (subgraphs.length() > 0) {
                full.append("\n").append(subgraphs);
            }
            if (edges.length() > 0) {
                full.append("\n").append(edges);
            }
            full.append("\n");
            appendStyles(full);
            return full.toString();
        }

        private void appendStyles(StringBuilder sb) {
            sb.append("    classDef startEnd fill:#f1f5f9,stroke:#475569,stroke-width:2px;\n")
                    .append("    classDef actionNode fill:#ffffff,stroke:#3b82f6,stroke-width:1.5px;\n")
                    .append("    classDef routeNode fill:#fef3c7,stroke:#d97706,stroke-width:1.5px;\n")
                    .append("    classDef parallelNode fill:#f3e8ff,stroke:#9333ea,stroke-width:1.5px;\n")
                    .append("    classDef awaitNode fill:#e0f2fe,stroke:#0284c7,stroke-width:1.5px;\n")
                    .append("    classDef successNode fill:#dcfce7,stroke:#166534,stroke-width:2px;\n")
                    .append("    classDef dangerNode fill:#fee2e2,stroke:#b91c1c,stroke-width:2px;\n");

            applyClass(sb, startEndNodes, "startEnd");
            applyClass(sb, actionNodes, "actionNode");
            applyClass(sb, routeNodes, "routeNode");
            applyClass(sb, parallelNodes, "parallelNode");
            applyClass(sb, awaitNodes, "awaitNode");
            applyClass(sb, successNodes, "successNode");
            applyClass(sb, dangerNodes, "dangerNode");
        }

        private void applyClass(StringBuilder sb, List<String> ids, String className) {
            if (!ids.isEmpty()) {
                sb.append("    class ").append(String.join(",", ids)).append(" ").append(className).append(";\n");
            }
        }
    }

    @Override
    public String render(FlowDescription description) {
        Objects.requireNonNull(description, "FlowDescription must not be null");

        RenderState state = new RenderState();
        String startId = "flow_start";
        String flowName = description.flowId() != null
                ? FlowGraphFormatters.escapeMermaid(description.flowId()) : "Flow";
        state.emitNode(startId, "([", "])", "开始: " + flowName);
        state.addStartEndNode(startId);

        // 1. 显式栈自底向上构建 Block
        Map<NodeDescription, NodeMeta> nodeMetas = new IdentityHashMap<NodeDescription, NodeMeta>();
        Map<NodeDescription, Block> blocks = new IdentityHashMap<NodeDescription, Block>();
        IdentityHashMap<NodeDescription, Boolean> scheduled = new IdentityHashMap<NodeDescription, Boolean>();

        Deque<Work> workStack = new ArrayDeque<Work>();
        workStack.addLast(new Work(description.root(), false));
        scheduled.put(description.root(), Boolean.TRUE);

        while (!workStack.isEmpty()) {
            Work item = workStack.removeLast();
            NodeDescription node = item.node;

            if (item.build) {
                NodeMeta meta = nodeMetas.get(node);
                Block block = buildNodeBlock(node, meta, blocks, state);
                blocks.put(node, block);
                continue;
            }

            // 遇到 CONTROL 节点：将修饰信息与 label 传递给子节点
            if (node.kind() == NodeDescriptor.Kind.CONTROL && !node.children().isEmpty()) {
                String badge = formatControlBadge(node);
                NodeMeta parentMeta = nodeMetas.get(node);
                NodeMeta childMeta = new NodeMeta();
                if (parentMeta != null) {
                    childMeta.badges.addAll(parentMeta.badges);
                    childMeta.inheritedLabel = parentMeta.inheritedLabel;
                }
                if (badge != null && !badge.isEmpty()) {
                    childMeta.badges.add(badge);
                }
                if (node.label().isPresent() && (childMeta.inheritedLabel == null || childMeta.inheritedLabel.isEmpty())) {
                    childMeta.inheritedLabel = node.label().get();
                }
                for (NodeDescription child : node.children()) {
                    nodeMetas.put(child, childMeta);
                }
            }

            workStack.addLast(new Work(node, true));
            List<NodeDescription> children = collectAllChildren(node);
            for (int index = children.size() - 1; index >= 0; index--) {
                NodeDescription child = children.get(index);
                if (scheduled.put(child, Boolean.TRUE) == null) {
                    workStack.addLast(new Work(child, false));
                }
            }
        }

        Block rootBlock = blocks.get(description.root());
        if (rootBlock != null) {
            state.emitEdge(startId, rootBlock.entryNodeId, null, false);
            if (!rootBlock.normalExitIds.isEmpty()) {
                String endId = "flow_end";
                state.emitNode(endId, "([", "])", "✅ 结束 (ACCEPTED)");
                state.addStartEndNode(endId);
                for (String exitId : rootBlock.normalExitIds) {
                    state.emitEdge(exitId, endId, null, false);
                }
            }
        }

        return state.build();
    }

    private static List<NodeDescription> collectAllChildren(NodeDescription node) {
        List<NodeDescription> children = new ArrayList<NodeDescription>();
        if (node.kind() == NodeDescriptor.Kind.ROUTE) {
            // Route 选择器直接合并入 Route 决策节点中，不作为独立子节点展开
            if (node.routeCases() != null) {
                for (RouteCaseDescription c : node.routeCases()) {
                    if (c.branch() != null) children.add(c.branch());
                }
            }
            if (node.otherwise() != null) {
                children.add(node.otherwise());
            }
            return children;
        }

        if (node.children() != null) {
            children.addAll(node.children());
        }
        if (node.parallelBranches() != null) {
            for (ParallelBranchDescription b : node.parallelBranches()) {
                if (b.branch() != null) children.add(b.branch());
            }
        }
        return children;
    }

    private static Block buildNodeBlock(NodeDescription node,
                                        NodeMeta meta,
                                        Map<NodeDescription, Block> blocks,
                                        RenderState state) {
        List<String> safeBadges = meta != null ? meta.badges : Collections.emptyList();
        String inheritedLabel = meta != null ? meta.inheritedLabel : null;

        switch (node.kind()) {
            case INVOKE:
                return renderInvoke(node, safeBadges, inheritedLabel, state);
            case SEQUENCE:
                return renderSequence(node, safeBadges, inheritedLabel, blocks, state);
            case ROUTE:
                return renderRoute(node, safeBadges, inheritedLabel, blocks, state);
            case FALLBACK:
                return renderFallback(node, safeBadges, blocks, state);
            case PARALLEL:
                return renderParallel(node, safeBadges, inheritedLabel, blocks, state);
            case AWAIT:
                return renderAwait(node, safeBadges, inheritedLabel, state);
            case COMPLETE:
                return renderComplete(node, safeBadges, inheritedLabel, state);
            case CONTROL:
                return renderControl(node, blocks, state);
            default:
                throw new IllegalStateException("Unknown node kind: " + node.kind());
        }
    }

    private static String formatControlBadge(NodeDescription controlNode) {
        String kind = controlNode.controlKind();
        if ("TIMEOUT".equalsIgnoreCase(kind)) {
            if (controlNode.configuration() instanceof Duration) {
                return "⏱️ " + FlowGraphFormatters.durationFriendly((Duration) controlNode.configuration());
            }
            return "⏱️ timeout";
        }
        if ("POLICY".equalsIgnoreCase(kind)) {
            if (controlNode.binding().isPresent() && controlNode.binding().get().qualifier().isPresent()) {
                return "🛡️ " + controlNode.binding().get().qualifier().get();
            }
            if (controlNode.binding().isPresent() && controlNode.binding().get().contractClass().isPresent()) {
                return "🛡️ " + FlowGraphFormatters.simpleClassName(controlNode.binding().get().contractClass().get());
            }
            return "🛡️ policy";
        }
        if ("PERSISTENT_POLICY".equalsIgnoreCase(kind)) {
            if (controlNode.binding().isPresent() && controlNode.binding().get().qualifier().isPresent()) {
                return "💾 " + controlNode.binding().get().qualifier().get();
            }
            return "💾 policy";
        }
        if ("RETRY".equalsIgnoreCase(kind)) {
            return "🔄 retry";
        }
        return kind != null ? "⚙️ " + kind.toLowerCase() : "";
    }

    private static String resolveEffectiveLabel(NodeDescription node, String inheritedLabel) {
        if (node.label().isPresent()) {
            return node.label().get();
        }
        return inheritedLabel;
    }

    private static Block renderInvoke(NodeDescription node, List<String> badges, String inheritedLabel, RenderState state) {
        String id = state.nextId();
        String badgeText = badges.isEmpty() ? "" : " " + String.join(" ", badges);

        String effectiveLabel = resolveEffectiveLabel(node, inheritedLabel);
        String title;
        String subtitle = "";
        if (effectiveLabel != null && !effectiveLabel.isEmpty()) {
            title = FlowGraphFormatters.escapeMermaid(effectiveLabel) + badgeText;
            if (node.binding().isPresent()) {
                subtitle = formatBindingSubtitle(node.binding().get());
            }
        } else {
            if (node.binding().isPresent()) {
                BindingDescriptor binding = node.binding().get();
                if (binding.contractClass().isPresent()) {
                    title = FlowGraphFormatters.escapeMermaid(FlowGraphFormatters.simpleClassName(binding.contractClass().get())) + badgeText;
                    if (binding.qualifier().isPresent()) {
                        subtitle = "(" + FlowGraphFormatters.escapeMermaid(binding.qualifier().get()) + ")";
                    }
                } else if (binding.qualifier().isPresent()) {
                    title = FlowGraphFormatters.escapeMermaid(binding.qualifier().get()) + badgeText;
                } else {
                    title = "Node" + badgeText;
                }
            } else {
                title = "Node" + badgeText;
            }
        }

        String content = subtitle.isEmpty() ? title : title + "<br/><small>" + subtitle + "</small>";
        state.emitNode(id, "[", "]", content);
        state.addActionNode(id);
        return new Block(id, Collections.singletonList(id), Collections.singletonList(id));
    }

    private static String formatBindingSubtitle(BindingDescriptor binding) {
        StringBuilder sb = new StringBuilder();
        if (binding.contractClass().isPresent()) {
            sb.append(FlowGraphFormatters.escapeMermaid(FlowGraphFormatters.simpleClassName(binding.contractClass().get())));
        }
        if (binding.qualifier().isPresent()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("(").append(FlowGraphFormatters.escapeMermaid(binding.qualifier().get())).append(")");
        }
        return sb.toString();
    }

    private static Block renderAwait(NodeDescription node, List<String> badges, String inheritedLabel, RenderState state) {
        String id = state.nextId();
        String badgeText = badges.isEmpty() ? "" : " " + String.join(" ", badges);
        String resumePoint = node.resumePoint() != null ? FlowGraphFormatters.display(node.resumePoint()) : "signal";

        String effectiveLabel = resolveEffectiveLabel(node, inheritedLabel);
        String title;
        String subtitle = "";
        if (effectiveLabel != null && !effectiveLabel.isEmpty()) {
            title = "⏳ " + FlowGraphFormatters.escapeMermaid(effectiveLabel) + badgeText;
            subtitle = "await: " + FlowGraphFormatters.escapeMermaid(resumePoint);
        } else {
            title = "⏳ 挂起等待: " + FlowGraphFormatters.escapeMermaid(resumePoint) + badgeText;
        }

        String content = subtitle.isEmpty() ? title : title + "<br/><small>" + subtitle + "</small>";
        state.emitNode(id, "[", "]", content);
        state.addAwaitNode(id);
        return new Block(id, Collections.singletonList(id), Collections.singletonList(id));
    }

    private static Block renderComplete(NodeDescription node, List<String> badges, String inheritedLabel, RenderState state) {
        String id = state.nextId();
        String badgeText = badges.isEmpty() ? "" : " " + String.join(" ", badges);
        String effectiveLabel = resolveEffectiveLabel(node, inheritedLabel);

        if (node.identity()) {
            String label = effectiveLabel != null && !effectiveLabel.isEmpty()
                    ? FlowGraphFormatters.escapeMermaid(effectiveLabel) + badgeText + " (透传)"
                    : "透传" + badgeText + " (Identity)";
            state.emitNode(id, "([", "])", label);
            state.addSuccessNode(id);
            return new Block(id, Collections.singletonList(id), Collections.singletonList(id));
        }
        Outcome<?> outcome = node.outcome();
        if (outcome == null) {
            state.emitNode(id, "([", "])", "COMPLETE" + badgeText);
            state.addActionNode(id);
            return new Block(id, Collections.singletonList(id), Collections.singletonList(id));
        }
        Outcome.Kind kind = outcome.kind();
        String customLabel = effectiveLabel != null && !effectiveLabel.isEmpty()
                ? FlowGraphFormatters.escapeMermaid(effectiveLabel) : null;
        switch (kind) {
            case ACCEPTED: {
                String title = "✅ " + (customLabel != null ? customLabel : "ACCEPTED") + badgeText;
                state.emitNode(id, "([", "])", title);
                state.addSuccessNode(id);
                return new Block(id, Collections.singletonList(id), Collections.singletonList(id));
            }
            case REJECTED: {
                String title = "❌ " + (customLabel != null ? customLabel : "REJECTED") + badgeText;
                state.emitNode(id, "([", "])", title);
                state.addDangerNode(id);
                return new Block(id, Collections.emptyList(), Collections.singletonList(id));
            }
            case SKIPPED: {
                String title = "⏭️ " + (customLabel != null ? customLabel : "SKIPPED") + badgeText;
                state.emitNode(id, "([", "])", title);
                state.addDangerNode(id);
                return new Block(id, Collections.emptyList(), Collections.singletonList(id));
            }
            case FAILED: {
                String title = "⚠️ " + (customLabel != null ? customLabel : "FAILED") + badgeText;
                state.emitNode(id, "([", "])", title);
                state.addDangerNode(id);
                return new Block(id, Collections.emptyList(), Collections.singletonList(id));
            }
            default: {
                String title = (customLabel != null ? customLabel : kind.name()) + badgeText;
                state.emitNode(id, "([", "])", title);
                state.addActionNode(id);
                return new Block(id, Collections.singletonList(id), Collections.singletonList(id));
            }
        }
    }

    private static Block renderRoute(NodeDescription node,
                                     List<String> badges,
                                     String inheritedLabel,
                                     Map<NodeDescription, Block> blocks,
                                     RenderState state) {
        String id = state.nextId();
        String badgeText = badges.isEmpty() ? "" : " " + String.join(" ", badges);

        NodeDescription selector = node.children().isEmpty() ? null : node.children().get(0);
        String effectiveLabel = resolveEffectiveLabel(node, inheritedLabel);
        String title;
        String subtitle = "";
        if (effectiveLabel != null && !effectiveLabel.isEmpty()) {
            title = FlowGraphFormatters.escapeMermaid(effectiveLabel) + badgeText;
            if (selector != null && selector.binding().isPresent()) {
                subtitle = formatBindingSubtitle(selector.binding().get());
            }
        } else if (selector != null && selector.label().isPresent()) {
            title = FlowGraphFormatters.escapeMermaid(selector.label().get()) + badgeText;
            if (selector.binding().isPresent()) {
                subtitle = formatBindingSubtitle(selector.binding().get());
            }
        } else if (selector != null && selector.binding().isPresent() && selector.binding().get().contractClass().isPresent()) {
            title = FlowGraphFormatters.escapeMermaid(FlowGraphFormatters.simpleClassName(selector.binding().get().contractClass().get())) + badgeText;
            if (selector.binding().get().qualifier().isPresent()) {
                subtitle = "(" + FlowGraphFormatters.escapeMermaid(selector.binding().get().qualifier().get()) + ")";
            }
        } else {
            title = "分支路由" + badgeText;
        }

        String content = subtitle.isEmpty() ? title : title + "<br/><small>" + subtitle + "</small>";
        state.emitNode(id, "{", "}", content);
        state.addRouteNode(id);

        List<String> normalExits = new ArrayList<String>();
        List<String> allNodes = new ArrayList<String>();
        allNodes.add(id);

        for (RouteCaseDescription c : node.routeCases()) {
            String caseKey = FlowGraphFormatters.escapeMermaid(FlowGraphFormatters.stableConstant(c.key()));
            Block branchBlock = blocks.get(c.branch());
            if (branchBlock != null) {
                state.emitEdge(id, branchBlock.entryNodeId, caseKey, false);
                normalExits.addAll(branchBlock.normalExitIds);
                allNodes.addAll(branchBlock.allNodeIds);
            }
        }

        if (node.otherwise() != null) {
            Block othBlock = blocks.get(node.otherwise());
            if (othBlock != null) {
                state.emitEdge(id, othBlock.entryNodeId, "otherwise", true);
                normalExits.addAll(othBlock.normalExitIds);
                allNodes.addAll(othBlock.allNodeIds);
            }
        } else {
            String noMatchId = state.nextId();
            state.emitNode(noMatchId, "([", "])", "⏭️ 未匹配 (SKIPPED)");
            state.addDangerNode(noMatchId);
            state.emitEdge(id, noMatchId, "no match", true);
            allNodes.add(noMatchId);
        }

        return new Block(id, normalExits, allNodes);
    }

    private static Block renderParallel(NodeDescription node,
                                        List<String> badges,
                                        String inheritedLabel,
                                        Map<NodeDescription, Block> blocks,
                                        RenderState state) {
        String forkId = state.nextId();
        String badgeText = badges.isEmpty() ? "" : " " + String.join(" ", badges);
        String effectiveLabel = resolveEffectiveLabel(node, inheritedLabel);
        String title = effectiveLabel != null && !effectiveLabel.isEmpty()
                ? FlowGraphFormatters.escapeMermaid(effectiveLabel) : "并行分发";
        state.emitNode(forkId, "{{", "}}", "并行: " + title + badgeText);
        state.addParallelNode(forkId);

        List<String> branchExits = new ArrayList<String>();
        List<String> allNodes = new ArrayList<String>();
        allNodes.add(forkId);

        for (ParallelBranchDescription b : node.parallelBranches()) {
            String branchName = FlowGraphFormatters.escapeMermaid(b.name());
            Block branchBlock = blocks.get(b.branch());
            if (branchBlock != null) {
                state.emitEdge(forkId, branchBlock.entryNodeId, branchName, false);
                branchExits.addAll(branchBlock.normalExitIds);
                allNodes.addAll(branchBlock.allNodeIds);
            }
        }

        String joinId = state.nextId();
        state.emitNode(joinId, "[", "]", "合并 (Join)");
        state.addActionNode(joinId);
        allNodes.add(joinId);

        for (String exitId : branchExits) {
            state.emitEdge(exitId, joinId, null, false);
        }
        return new Block(forkId, Collections.singletonList(joinId), allNodes);
    }

    private static Block renderFallback(NodeDescription node,
                                        List<String> badges,
                                        Map<NodeDescription, Block> blocks,
                                        RenderState state) {
        List<NodeDescription> children = node.children();
        if (children.isEmpty()) {
            String id = state.nextId();
            state.emitNode(id, "([", "])", "FALLBACK");
            return new Block(id, Collections.singletonList(id), Collections.singletonList(id));
        }
        Block mainBlock = blocks.get(children.get(0));
        if (mainBlock == null) {
            String id = state.nextId();
            return new Block(id, Collections.singletonList(id), Collections.singletonList(id));
        }
        if (children.size() > 1) {
            Block fallbackBlock = blocks.get(children.get(1));
            if (fallbackBlock != null) {
                String triggerLabel = "FAILED".equals(node.trigger()) ? "FAILED (降级)" : "SKIPPED (备用)";
                state.emitEdge(mainBlock.entryNodeId, fallbackBlock.entryNodeId, triggerLabel, true);
                List<String> combinedExits = new ArrayList<String>(mainBlock.normalExitIds);
                combinedExits.addAll(fallbackBlock.normalExitIds);

                List<String> allNodes = new ArrayList<String>(mainBlock.allNodeIds);
                allNodes.addAll(fallbackBlock.allNodeIds);
                return new Block(mainBlock.entryNodeId, combinedExits, allNodes);
            }
        }
        return mainBlock;
    }

    private static Block renderSequence(NodeDescription node,
                                        List<String> badges,
                                        String inheritedLabel,
                                        Map<NodeDescription, Block> blocks,
                                        RenderState state) {
        List<NodeDescription> children = node.children();
        if (children.isEmpty()) {
            String id = state.nextId();
            state.emitNode(id, "([", "])", "EMPTY");
            return new Block(id, Collections.singletonList(id), Collections.singletonList(id));
        }

        String firstEntry = null;
        Block prev = null;
        List<String> allNodes = new ArrayList<String>();

        for (NodeDescription child : children) {
            Block curr = blocks.get(child);
            if (curr == null) continue;
            allNodes.addAll(curr.allNodeIds);
            if (firstEntry == null) {
                firstEntry = curr.entryNodeId;
            }
            if (prev != null) {
                for (String exitId : prev.normalExitIds) {
                    state.emitEdge(exitId, curr.entryNodeId, null, false);
                }
            }
            prev = curr;
        }

        String effectiveLabel = resolveEffectiveLabel(node, inheritedLabel);
        boolean hasScope = (node.scopeName() != null && !node.scopeName().isEmpty())
                || (effectiveLabel != null && !effectiveLabel.isEmpty());

        if (hasScope && !allNodes.isEmpty()) {
            String scopeId = "sg_" + state.nextId();
            String scopeTitle;
            if (node.scopeName() != null && !node.scopeName().isEmpty()) {
                scopeTitle = "作用域: " + FlowGraphFormatters.escapeMermaid(node.scopeName());
            } else {
                scopeTitle = FlowGraphFormatters.escapeMermaid(effectiveLabel);
            }
            if (!badges.isEmpty()) {
                scopeTitle += " " + String.join(" ", badges);
            }
            state.emitSubgraph(scopeId, scopeTitle, allNodes);
        }

        return new Block(firstEntry != null ? firstEntry : state.nextId(),
                prev != null ? prev.normalExitIds : Collections.emptyList(),
                allNodes);
    }

    private static Block renderControl(NodeDescription node,
                                       Map<NodeDescription, Block> blocks,
                                       RenderState state) {
        if (!node.children().isEmpty()) {
            Block child = blocks.get(node.children().get(0));
            if (child != null) return child;
        }
        String id = state.nextId();
        String badge = formatControlBadge(node);
        state.emitNode(id, "[", "]", badge.isEmpty() ? "CONTROL" : badge);
        state.addActionNode(id);
        return new Block(id, Collections.singletonList(id), Collections.singletonList(id));
    }
}
