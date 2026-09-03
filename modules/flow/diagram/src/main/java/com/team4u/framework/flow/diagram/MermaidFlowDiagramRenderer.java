package com.team4u.framework.flow.diagram;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 业务友好型确定性 Mermaid 流程图渲染器（纯净默认主题风格）。
 *
 * <p>将 {@link FlowDescription} 静态拓扑描述模型渲染为直观、清晰、规范的 Mermaid 流程图（{@code flowchart TD}）。
 * 采用显式工作栈消除 JVM 递归开销，自动折叠底层 AST 胶水节点，采用 Mermaid 默认原生主题渲染。</p>
 *
 * <p><b>作用域与嵌套策略：</b>具名作用域（{@code Flow.scope}）与带标签的顺序节点渲染为 Mermaid {@code subgraph}。
 * 嵌套作用域按 Block 树物理嵌套输出（内层 {@code subgraph...end} 完整位于外层块内部），
 * 节点声明行只出现一次且位于其最内层所属 subgraph 中，保证节点归属语义正确。</p>
 *
 * <p><b>共享子图（DAG 复用）策略：</b>当描述树中同一子树实例被多处复用时，本渲染器<b>折叠共享节点</b>
 * （每个节点仅声明一次，归属最先构建的路径），而 {@link TextFlowDiagramRenderer} 按先序遍历对共享子树重复输出。
 * 两者策略差异属预期行为。</p>
 *
 * @author jay.wu
 */
final class MermaidFlowDiagramRenderer implements FlowDiagramRenderer {

    static final MermaidFlowDiagramRenderer INSTANCE = new MermaidFlowDiagramRenderer();

    /** subgraph 缩进层级上限，防止超深嵌套导致行首空白膨胀。 */
    private static final int MAX_INDENT_DEPTH = 8;

    private static final class Section {
        final String id;
        final String title;
        final List<String> memberIds = new ArrayList<String>();
        final List<Section> nested = new ArrayList<Section>();

        Section(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private static final class Block {
        final String entryNodeId;
        final List<String> normalExitIds;
        final List<Section> sections;
        final List<String> looseNodeIds;

        Block(String entryNodeId,
              List<String> normalExitIds,
              List<Section> sections,
              List<String> looseNodeIds) {
            this.entryNodeId = entryNodeId;
            this.normalExitIds = normalExitIds != null ? normalExitIds : Collections.<String>emptyList();
            this.sections = sections != null ? sections : Collections.<Section>emptyList();
            this.looseNodeIds = looseNodeIds != null ? looseNodeIds : Collections.<String>emptyList();
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
        private final LinkedHashMap<String, String> nodeLines = new LinkedHashMap<String, String>();
        private final StringBuilder edges = new StringBuilder();
        private int sequence;

        private String nextId() {
            sequence++;
            return "n" + sequence;
        }

        private void emitNode(String id, String open, String close, String label) {
            nodeLines.put(id, id + open + "\"" + label + "\"" + close);
        }

        private void emitEdge(String source, String target, String label, boolean dashed) {
            edges.append("    ").append(source);
            if (dashed) {
                edges.append(" -.->");
            } else {
                edges.append(" -->");
            }
            if (label != null && !label.isEmpty()) {
                edges.append("|").append(FlowDiagramFormatters.escapeMermaid(label)).append("|");
            }
            edges.append(" ").append(target).append("\n");
        }

        private String build(List<String> rootLooseIds, List<Section> rootSections) {
            StringBuilder full = new StringBuilder();
            full.append("flowchart TD\n");
            for (String id : rootLooseIds) {
                String line = nodeLines.get(id);
                if (line != null) {
                    full.append("    ").append(line).append("\n");
                }
            }
            if (!rootSections.isEmpty()) {
                full.append("\n");
                appendSections(full, rootSections);
            }
            if (edges.length() > 0) {
                full.append("\n").append(edges);
            }
            return full.toString();
        }

        /**
         * 按物理嵌套输出 subgraph 树（显式栈迭代，深嵌套安全且成本线性）。
         * 栈中的 token 为 {@link SectionFrame}（输出 subgraph 头并展开成员与子块）
         * 或 {@link Integer}（与 subgraph 头同缩进的 {@code end} 行标记）。
         */
        private void appendSections(StringBuilder out, List<Section> sections) {
            Deque<Object> stack = new ArrayDeque<Object>();
            for (int index = sections.size() - 1; index >= 0; index--) {
                stack.addLast(Integer.valueOf(1));
                stack.addLast(new SectionFrame(sections.get(index), 1));
            }
            while (!stack.isEmpty()) {
                Object token = stack.removeLast();
                if (token instanceof Integer) {
                    appendIndent(out, ((Integer) token).intValue()).append("end\n");
                    continue;
                }
                SectionFrame frame = (SectionFrame) token;
                appendIndent(out, frame.depth)
                        .append("subgraph ").append(frame.section.id)
                        .append(" [\"").append(frame.section.title).append("\"]\n");
                int memberDepth = frame.depth + 1;
                for (String memberId : frame.section.memberIds) {
                    String line = nodeLines.get(memberId);
                    appendIndent(out, memberDepth)
                            .append(line != null ? line : memberId).append("\n");
                }
                for (int index = frame.section.nested.size() - 1; index >= 0; index--) {
                    stack.addLast(Integer.valueOf(memberDepth));
                    stack.addLast(new SectionFrame(frame.section.nested.get(index), memberDepth));
                }
            }
        }

        private static StringBuilder appendIndent(StringBuilder out, int depth) {
            int bounded = Math.min(depth, MAX_INDENT_DEPTH);
            for (int i = 0; i < bounded * 4; i++) {
                out.append(' ');
            }
            return out;
        }

        private static final class SectionFrame {
            final Section section;
            final int depth;

            SectionFrame(Section section, int depth) {
                this.section = section;
                this.depth = depth;
            }
        }
    }

    @Override
    public String render(FlowDescription description) {
        Objects.requireNonNull(description, "FlowDescription must not be null");

        RenderState state = new RenderState();
        String startId = "flow_start";
        String flowName = description.flowId() != null
                ? FlowDiagramFormatters.escapeMermaid(description.flowId()) : "Flow";
        state.emitNode(startId, "([", "])", "开始: " + flowName);

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
            } else if (node.kind() == NodeDescriptor.Kind.FALLBACK && !node.children().isEmpty()) {
                // FALLBACK 继承外层修饰并传递给主分支 (child 0)
                NodeMeta parentMeta = nodeMetas.get(node);
                if (parentMeta != null) {
                    NodeDescription mainBranch = node.children().get(0);
                    NodeMeta mainMeta = nodeMetas.get(mainBranch);
                    if (mainMeta == null) {
                        mainMeta = new NodeMeta();
                        nodeMetas.put(mainBranch, mainMeta);
                    }
                    mainMeta.badges.addAll(parentMeta.badges);
                    if (mainMeta.inheritedLabel == null) {
                        mainMeta.inheritedLabel = parentMeta.inheritedLabel;
                    }
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
        String endId = null;
        if (rootBlock != null) {
            state.emitEdge(startId, rootBlock.entryNodeId, null, false);
            if (!rootBlock.normalExitIds.isEmpty()) {
                endId = "flow_end";
                state.emitNode(endId, "([", "])", "结束 (ACCEPTED)");
                for (String exitId : rootBlock.normalExitIds) {
                    state.emitEdge(exitId, endId, null, false);
                }
            }
        }

        List<String> rootLooseIds = new ArrayList<String>();
        rootLooseIds.add(startId);
        if (rootBlock != null) {
            rootLooseIds.addAll(rootBlock.looseNodeIds);
        }
        if (endId != null) {
            rootLooseIds.add(endId);
        }
        List<Section> rootSections = rootBlock != null
                ? rootBlock.sections : Collections.<Section>emptyList();

        return state.build(rootLooseIds, rootSections);
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
        if (node.kind() == NodeDescriptor.Kind.PARALLEL) {
            if (node.parallelBranches() != null) {
                for (ParallelBranchDescription b : node.parallelBranches()) {
                    if (b.branch() != null) children.add(b.branch());
                }
            }
            return children;
        }
        if (node.children() != null) {
            children.addAll(node.children());
        }
        return children;
    }

    private static Block buildNodeBlock(NodeDescription node,
                                        NodeMeta meta,
                                        Map<NodeDescription, Block> blocks,
                                        RenderState state) {
        List<String> safeBadges = meta != null ? meta.badges : Collections.<String>emptyList();
        String inheritedLabel = meta != null ? meta.inheritedLabel : null;

        switch (node.kind()) {
            case INVOKE:
                return renderInvoke(node, safeBadges, inheritedLabel, state);
            case SEQUENCE:
                return renderSequence(node, safeBadges, inheritedLabel, blocks, state);
            case ROUTE:
                return renderRoute(node, safeBadges, inheritedLabel, blocks, state);
            case FALLBACK:
                return renderFallback(node, blocks, state);
            case PARALLEL:
                return renderParallel(node, safeBadges, inheritedLabel, blocks, state);
            case AWAIT:
                return renderAwait(node, safeBadges, inheritedLabel, state);
            case COMPLETE:
                return renderComplete(node, safeBadges, inheritedLabel, state);
            case CONTROL:
                return renderControl(node, blocks, state);
            case ADAPTER:
                return renderAdapter(node, blocks, state);
            default:
                throw new IllegalStateException("Unknown node kind: " + node.kind());
        }
    }

    private static String formatControlBadge(NodeDescription controlNode) {
        String kind = controlNode.controlKind();
        if ("TIMEOUT".equalsIgnoreCase(kind)) {
            if (controlNode.configuration() instanceof Duration) {
                return "[timeout: " + FlowDiagramFormatters.escapeMermaid(FlowDiagramFormatters.durationFriendly((Duration) controlNode.configuration())) + "]";
            }
            return "[timeout]";
        }
        if ("POLICY".equalsIgnoreCase(kind)) {
            if (controlNode.binding().isPresent() && controlNode.binding().get().qualifier().isPresent()) {
                return "[policy: " + FlowDiagramFormatters.escapeMermaid(controlNode.binding().get().qualifier().get()) + "]";
            }
            if (controlNode.binding().isPresent() && controlNode.binding().get().contractClass().isPresent()) {
                return "[policy: " + FlowDiagramFormatters.escapeMermaid(FlowDiagramFormatters.simpleClassName(controlNode.binding().get().contractClass().get())) + "]";
            }
            return "[policy]";
        }
        if ("PERSISTENT_POLICY".equalsIgnoreCase(kind)) {
            if (controlNode.binding().isPresent() && controlNode.binding().get().qualifier().isPresent()) {
                return "[persistent: " + FlowDiagramFormatters.escapeMermaid(controlNode.binding().get().qualifier().get()) + "]";
            }
            if (controlNode.binding().isPresent() && controlNode.binding().get().contractClass().isPresent()) {
                return "[persistent: " + FlowDiagramFormatters.escapeMermaid(FlowDiagramFormatters.simpleClassName(controlNode.binding().get().contractClass().get())) + "]";
            }
            return "[persistent-policy]";
        }
        if ("RETRY".equalsIgnoreCase(kind)) {
            return "[retry]";
        }
        return kind != null ? "[" + FlowDiagramFormatters.escapeMermaid(kind.toLowerCase()) + "]" : "";
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
            title = FlowDiagramFormatters.escapeMermaid(effectiveLabel) + badgeText;
            if (node.binding().isPresent()) {
                subtitle = formatBindingSubtitle(node.binding().get());
            }
        } else {
            if (node.binding().isPresent()) {
                BindingDescriptor binding = node.binding().get();
                if (binding.contractClass().isPresent()) {
                    title = FlowDiagramFormatters.escapeMermaid(FlowDiagramFormatters.simpleClassName(binding.contractClass().get())) + badgeText;
                    if (binding.qualifier().isPresent()) {
                        subtitle = "(" + FlowDiagramFormatters.escapeMermaid(binding.qualifier().get()) + ")";
                    }
                } else if (binding.qualifier().isPresent()) {
                    title = FlowDiagramFormatters.escapeMermaid(binding.qualifier().get()) + badgeText;
                } else {
                    title = "Node" + badgeText;
                }
            } else {
                title = "Node" + badgeText;
            }
        }

        String content = subtitle.isEmpty() ? title : title + "<br/>" + subtitle;
        state.emitNode(id, "[", "]", content);
        return new Block(id, Collections.singletonList(id),
                Collections.<Section>emptyList(), Collections.singletonList(id));
    }

    private static String formatBindingSubtitle(BindingDescriptor binding) {
        StringBuilder sb = new StringBuilder();
        if (binding.contractClass().isPresent()) {
            sb.append(FlowDiagramFormatters.escapeMermaid(FlowDiagramFormatters.simpleClassName(binding.contractClass().get())));
        }
        if (binding.qualifier().isPresent()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("(").append(FlowDiagramFormatters.escapeMermaid(binding.qualifier().get())).append(")");
        }
        return sb.toString();
    }

    private static Block renderAwait(NodeDescription node, List<String> badges, String inheritedLabel, RenderState state) {
        String id = state.nextId();
        String badgeText = badges.isEmpty() ? "" : " " + String.join(" ", badges);
        String resumePoint = node.resumePoint() != null ? FlowDiagramFormatters.display(node.resumePoint()) : "signal";

        String effectiveLabel = resolveEffectiveLabel(node, inheritedLabel);
        String title;
        String subtitle = "";
        if (effectiveLabel != null && !effectiveLabel.isEmpty()) {
            title = FlowDiagramFormatters.escapeMermaid(effectiveLabel) + badgeText;
            subtitle = "await: " + FlowDiagramFormatters.escapeMermaid(resumePoint);
        } else {
            title = "挂起等待: " + FlowDiagramFormatters.escapeMermaid(resumePoint) + badgeText;
        }

        String content = subtitle.isEmpty() ? title : title + "<br/>" + subtitle;
        state.emitNode(id, "[", "]", content);
        return new Block(id, Collections.singletonList(id),
                Collections.<Section>emptyList(), Collections.singletonList(id));
    }

    private static Block renderComplete(NodeDescription node, List<String> badges, String inheritedLabel, RenderState state) {
        String id = state.nextId();
        String badgeText = badges.isEmpty() ? "" : " " + String.join(" ", badges);
        String effectiveLabel = resolveEffectiveLabel(node, inheritedLabel);

        if (node.identity()) {
            String label = effectiveLabel != null && !effectiveLabel.isEmpty()
                    ? FlowDiagramFormatters.escapeMermaid(effectiveLabel) + badgeText + " (透传)"
                    : "透传" + badgeText + " (Identity)";
            state.emitNode(id, "([", "])", label);
            return new Block(id, Collections.singletonList(id),
                    Collections.<Section>emptyList(), Collections.singletonList(id));
        }
        Outcome<?> outcome = node.outcome();
        if (outcome == null) {
            state.emitNode(id, "([", "])", "COMPLETE" + badgeText);
            return new Block(id, Collections.singletonList(id),
                    Collections.<Section>emptyList(), Collections.singletonList(id));
        }
        Outcome.Kind kind = outcome.kind();
        String customLabel = effectiveLabel != null && !effectiveLabel.isEmpty()
                ? FlowDiagramFormatters.escapeMermaid(effectiveLabel) : null;
        switch (kind) {
            case ACCEPTED: {
                String title = (customLabel != null ? customLabel : "[ACCEPTED]") + badgeText;
                state.emitNode(id, "([", "])", title);
                return new Block(id, Collections.singletonList(id),
                        Collections.<Section>emptyList(), Collections.singletonList(id));
            }
            case REJECTED: {
                String title = (customLabel != null ? customLabel : "[REJECTED]") + badgeText;
                state.emitNode(id, "([", "])", title);
                return new Block(id, Collections.<String>emptyList(),
                        Collections.<Section>emptyList(), Collections.singletonList(id));
            }
            case SKIPPED: {
                String title = (customLabel != null ? customLabel : "[SKIPPED]") + badgeText;
                state.emitNode(id, "([", "])", title);
                return new Block(id, Collections.<String>emptyList(),
                        Collections.<Section>emptyList(), Collections.singletonList(id));
            }
            case FAILED: {
                String title = (customLabel != null ? customLabel : "[FAILED]") + badgeText;
                state.emitNode(id, "([", "])", title);
                return new Block(id, Collections.<String>emptyList(),
                        Collections.<Section>emptyList(), Collections.singletonList(id));
            }
            default: {
                String title = (customLabel != null ? customLabel : kind.name()) + badgeText;
                state.emitNode(id, "([", "])", title);
                return new Block(id, Collections.singletonList(id),
                        Collections.<Section>emptyList(), Collections.singletonList(id));
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
            title = FlowDiagramFormatters.escapeMermaid(effectiveLabel) + badgeText;
            if (selector != null && selector.binding().isPresent()) {
                subtitle = formatBindingSubtitle(selector.binding().get());
            }
        } else if (selector != null && selector.label().isPresent()) {
            title = FlowDiagramFormatters.escapeMermaid(selector.label().get()) + badgeText;
            if (selector.binding().isPresent()) {
                subtitle = formatBindingSubtitle(selector.binding().get());
            }
        } else if (selector != null && selector.binding().isPresent() && selector.binding().get().contractClass().isPresent()) {
            title = FlowDiagramFormatters.escapeMermaid(FlowDiagramFormatters.simpleClassName(selector.binding().get().contractClass().get())) + badgeText;
            if (selector.binding().get().qualifier().isPresent()) {
                subtitle = "(" + FlowDiagramFormatters.escapeMermaid(selector.binding().get().qualifier().get()) + ")";
            }
        } else {
            title = "分支路由" + badgeText;
        }

        String content = subtitle.isEmpty() ? title : title + "<br/>" + subtitle;
        state.emitNode(id, "{", "}", content);

        List<String> normalExits = new ArrayList<String>();
        List<Section> sections = new ArrayList<Section>();
        List<String> looseIds = new ArrayList<String>();
        looseIds.add(id);

        for (RouteCaseDescription c : node.routeCases()) {
            String caseKey = FlowDiagramFormatters.stableConstant(c.key());
            Block branchBlock = blocks.get(c.branch());
            if (branchBlock != null) {
                state.emitEdge(id, branchBlock.entryNodeId, caseKey, false);
                normalExits.addAll(branchBlock.normalExitIds);
                sections.addAll(branchBlock.sections);
                looseIds.addAll(branchBlock.looseNodeIds);
            }
        }

        if (node.otherwise() != null) {
            Block othBlock = blocks.get(node.otherwise());
            if (othBlock != null) {
                state.emitEdge(id, othBlock.entryNodeId, "otherwise", true);
                normalExits.addAll(othBlock.normalExitIds);
                sections.addAll(othBlock.sections);
                looseIds.addAll(othBlock.looseNodeIds);
            }
        } else {
            String noMatchId = state.nextId();
            state.emitNode(noMatchId, "([", "])", "未匹配 (SKIPPED)");
            state.emitEdge(id, noMatchId, "no match", true);
            looseIds.add(noMatchId);
        }

        return new Block(id, normalExits, sections, looseIds);
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
                ? FlowDiagramFormatters.escapeMermaid(effectiveLabel) : "并行分发";
        state.emitNode(forkId, "{{", "}}", "并行: " + title + badgeText);

        List<String> branchExits = new ArrayList<String>();
        List<Section> sections = new ArrayList<Section>();
        List<String> looseIds = new ArrayList<String>();
        looseIds.add(forkId);

        for (ParallelBranchDescription b : node.parallelBranches()) {
            String branchName = b.name();
            Block branchBlock = blocks.get(b.branch());
            if (branchBlock != null) {
                state.emitEdge(forkId, branchBlock.entryNodeId, branchName, false);
                branchExits.addAll(branchBlock.normalExitIds);
                sections.addAll(branchBlock.sections);
                looseIds.addAll(branchBlock.looseNodeIds);
            }
        }

        String joinId = state.nextId();
        state.emitNode(joinId, "[", "]", "合并 (Join)");
        looseIds.add(joinId);

        for (String exitId : branchExits) {
            state.emitEdge(exitId, joinId, null, false);
        }
        return new Block(forkId, Collections.singletonList(joinId), sections, looseIds);
    }

    private static Block renderFallback(NodeDescription node,
                                        Map<NodeDescription, Block> blocks,
                                        RenderState state) {
        List<NodeDescription> children = node.children();
        if (children.isEmpty()) {
            String id = state.nextId();
            state.emitNode(id, "([", "])", "FALLBACK");
            return new Block(id, Collections.singletonList(id),
                    Collections.<Section>emptyList(), Collections.singletonList(id));
        }
        Block mainBlock = blocks.get(children.get(0));
        if (mainBlock == null) {
            // 子块在显式栈构建协议下必然先于父块完成；到达此分支说明协议被破坏。
            throw new IllegalStateException(
                    "Fallback main branch block was not built before parent node: " + node.path());
        }
        if (children.size() > 1) {
            Block fallbackBlock = blocks.get(children.get(1));
            if (fallbackBlock != null) {
                String triggerLabel = "FAILED".equals(node.trigger()) ? "FAILED 降级" : "SKIPPED 备用";
                state.emitEdge(mainBlock.entryNodeId, fallbackBlock.entryNodeId, triggerLabel, true);
                List<String> combinedExits = new ArrayList<String>(mainBlock.normalExitIds);
                combinedExits.addAll(fallbackBlock.normalExitIds);

                List<Section> sections = new ArrayList<Section>(mainBlock.sections);
                sections.addAll(fallbackBlock.sections);
                List<String> looseIds = new ArrayList<String>(mainBlock.looseNodeIds);
                looseIds.addAll(fallbackBlock.looseNodeIds);
                return new Block(mainBlock.entryNodeId, combinedExits, sections, looseIds);
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
            return new Block(id, Collections.singletonList(id),
                    Collections.<Section>emptyList(), Collections.singletonList(id));
        }

        String firstEntry = null;
        Block prev = null;
        List<Section> sections = new ArrayList<Section>();
        List<String> looseIds = new ArrayList<String>();

        for (NodeDescription child : children) {
            Block curr = blocks.get(child);
            if (curr == null) continue;
            sections.addAll(curr.sections);
            looseIds.addAll(curr.looseNodeIds);
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
        if (firstEntry == null) {
            // 子块在显式栈构建协议下必然先于父块完成；到达此分支说明协议被破坏。
            throw new IllegalStateException(
                    "Sequence child blocks were not built before parent node: " + node.path());
        }

        String effectiveLabel = resolveEffectiveLabel(node, inheritedLabel);
        boolean hasScope = (node.scopeName() != null && !node.scopeName().isEmpty())
                || (effectiveLabel != null && !effectiveLabel.isEmpty());

        if (hasScope && !(looseIds.isEmpty() && sections.isEmpty())) {
            String scopeId = "sg_" + state.nextId();
            String scopeTitle;
            if (node.scopeName() != null && !node.scopeName().isEmpty()) {
                scopeTitle = "作用域: " + FlowDiagramFormatters.escapeMermaid(node.scopeName());
            } else {
                scopeTitle = FlowDiagramFormatters.escapeMermaid(effectiveLabel);
            }
            if (!badges.isEmpty()) {
                scopeTitle += " " + String.join(" ", badges);
            }
            Section section = new Section(scopeId, scopeTitle);
            section.memberIds.addAll(looseIds);
            section.nested.addAll(sections);
            return new Block(firstEntry, prev.normalExitIds,
                    Collections.singletonList(section), Collections.<String>emptyList());
        }

        return new Block(firstEntry, prev.normalExitIds, sections, looseIds);
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
        return new Block(id, Collections.singletonList(id),
                Collections.<Section>emptyList(), Collections.singletonList(id));
    }

    private static Block renderAdapter(NodeDescription node,
                                       Map<NodeDescription, Block> blocks,
                                       RenderState state) {
        if (!node.children().isEmpty()) {
            Block child = blocks.get(node.children().get(0));
            if (child != null) return child;
        }
        String id = state.nextId();
        state.emitNode(id, "[", "]", "ADAPTER");
        return new Block(id, Collections.singletonList(id),
                Collections.<Section>emptyList(), Collections.singletonList(id));
    }
}
