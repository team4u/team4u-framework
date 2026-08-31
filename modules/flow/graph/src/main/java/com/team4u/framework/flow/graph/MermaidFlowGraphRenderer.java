package com.team4u.framework.flow.graph;


import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.desc.NodeDescription;
import com.team4u.framework.flow.spi.BindingDescriptor;

/**
 * 确定性 Mermaid 流程图渲染器（Deterministic Mermaid Flow Graph Renderer）。
 *
 * <p>基于 {@link FlowDescription} 静态拓扑描述模型，生成标准、美观且具有确定性节点 ID 与样式的 Mermaid 流程图代码（{@code flowchart TD}）。
 * 采用 Rope 树状出口集合（Exits）与显式工作栈，保证深层嵌套流程图渲染时无额外内存拷贝与栈溢出风险。</p>
 *
 * @author jay.wu
 */
final class MermaidFlowGraphRenderer implements FlowGraphRenderer {

    static final MermaidFlowGraphRenderer INSTANCE = new MermaidFlowGraphRenderer();


    private enum Channel {
        ACCEPTED, REJECTED, SKIPPED, FAILED, SUSPENDED, CANCELLED
    }

    @lombok.AllArgsConstructor
    private static final class Exit {
        private final String source;
        private final Channel channel;
    }

    /**
     * 出口集合的不可变组合（rope）：追加与合并均为 O(1) 且结构共享，
     * 嵌套组合不会随层数放大复制成本；遍历必须使用显式栈，禁止递归。
     */
    private abstract static class Exits {
        static final Exits EMPTY = EmptyExits.INSTANCE;

        static Exits single(Exit exit) {
            return new SingleExit(exit);
        }

        static Exits concat(Exits left, Exits right) {
            if (left == EMPTY) {
                return right;
            }
            if (right == EMPTY) {
                return left;
            }
            return new ConcatExits(left, right);
        }
    }

    private static final class EmptyExits extends Exits {
        static final EmptyExits INSTANCE = new EmptyExits();
    }

    @lombok.AllArgsConstructor
    private static final class SingleExit extends Exits {
        private final Exit exit;
    }

    @lombok.AllArgsConstructor
    private static final class ConcatExits extends Exits {
        private final Exits left;
        private final Exits right;
    }

    private interface ExitVisitor {
        void visit(Exit exit);
    }

    @lombok.RequiredArgsConstructor
    private static final class Fragment {
        private final String entry;
        private Exits exits = Exits.EMPTY;

        private void add(String source, Channel channel) {
            add(new Exit(source, channel));
        }

        private void add(Exit exit) {
            exits = Exits.concat(exits, Exits.single(exit));
        }

        private void addAll(Fragment fragment) {
            exits = Exits.concat(exits, fragment.exits);
        }

        private void addExcept(final Fragment fragment, final Channel excluded) {
            forEachExit(fragment.exits, new ExitVisitor() {
                @Override
                public void visit(Exit exit) {
                    if (exit.channel != excluded) {
                        add(exit);
                    }
                }
            });
        }
    }

    private static final class Work {
        private final NodeDescription node;
        private final boolean build;

        private Work(NodeDescription node, boolean build) {
            this.node = node;
            this.build = build;
        }
    }

    private static final class RenderState {
        private final StringBuilder output = new StringBuilder();
        private int sequence;

        private String nextId() {
            sequence++;
            return "n" + sequence;
        }

        private void node(String id, String open, String close, String label) {
            output.append("    ").append(id).append(open).append("\"")
                    .append(escape(label)).append("\"").append(close).append("\n");
        }

        private void edge(String source, String target, String label, Channel channel) {
            output.append("    ").append(source);
            if (channel == Channel.FAILED || channel == Channel.SKIPPED
                    || channel == Channel.SUSPENDED || channel == Channel.CANCELLED) {
                output.append(" -.->");
            } else {
                output.append(" -->");
            }
            if (label != null && !label.isEmpty()) {
                output.append("|").append(escape(label)).append("|");
            }
            output.append(" ").append(target).append("\n");
        }
    }

    @Override
    public String render(FlowDescription description) {
        Objects.requireNonNull(description, "FlowDescription must not be null");

        RenderState state = new RenderState();
        state.output.append("flowchart TD\n");
        state.node("flow_start", "([", "])", "START | flow=" + display(description.flowId()));

        IdentityHashMap<NodeDescription, Fragment> fragments =
                new IdentityHashMap<NodeDescription, Fragment>();
        IdentityHashMap<NodeDescription, Boolean> scheduled =
                new IdentityHashMap<NodeDescription, Boolean>();
        Deque<Work> work = new ArrayDeque<Work>();
        scheduled.put(description.root(), Boolean.TRUE);
        work.addLast(new Work(description.root(), false));
        while (!work.isEmpty()) {
            Work item = work.removeLast();
            if (item.build) {
                fragments.put(item.node, build(item.node, fragments, state));
                continue;
            }
            work.addLast(new Work(item.node, true));
            List<NodeDescription> children = item.node.children();
            for (int index = children.size() - 1; index >= 0; index--) {
                NodeDescription child = children.get(index);
                if (scheduled.put(child, Boolean.TRUE) == null) {
                    work.addLast(new Work(child, false));
                }
            }
        }

        appendTerminals(state);
        Fragment root = required(fragments, description.root());
        state.edge("flow_start", root.entry, null, null);
        state.edge("flow_start", "terminal_cancelled", "CANCELLED", Channel.CANCELLED);
        for (Exit exit : allExits(root)) {
            state.edge(exit.source, terminal(exit.channel), exit.channel.name(), exit.channel);
        }
        appendStyles(state.output);
        return state.output.toString();
    }

    private static Fragment build(NodeDescription node,
                                  IdentityHashMap<NodeDescription, Fragment> fragments,
                                  RenderState state) {
        switch (node.kind()) {
            case INVOKE:
                return invoke(node, state);
            case SEQUENCE:
                return sequence(node, fragments, state);
            case ROUTE:
                return route(node, fragments, state);
            case FALLBACK:
                return fallback(node, fragments, state);
            case PARALLEL:
                return parallel(node, fragments, state);
            case AWAIT:
                return await(node, state);
            case CONTROL:
                return control(node, fragments, state);
            case COMPLETE:
                return complete(node, state);
            default:
                throw new IllegalStateException("Unknown node kind: " + node.kind());
        }
    }

    private static Fragment invoke(NodeDescription node, RenderState state) {
        String id = state.nextId();
        state.node(id, "[", "]", metadata(node) + binding(node));
        Fragment result = new Fragment(id);
        addBusinessOutcomes(result, id);
        return result;
    }

    private static Fragment sequence(NodeDescription node,
                                     IdentityHashMap<NodeDescription, Fragment> fragments,
                                     RenderState state) {
        String id = state.nextId();
        String scope = node.scopeName() == null ? "anonymous" : display(node.scopeName());
        state.node(id, "[[", "]]", metadata(node) + " | scope=" + scope);
        Fragment result = new Fragment(id);
        List<NodeDescription> children = node.children();
        if (children.isEmpty()) {
            result.add(id, Channel.ACCEPTED);
            return result;
        }

        Fragment first = required(fragments, children.get(0));
        state.edge(id, first.entry, "enter", null);
        List<Exit> currentAccepted = channelExits(first, Channel.ACCEPTED);
        result.addExcept(first, Channel.ACCEPTED);
        for (int index = 1; index < children.size(); index++) {
            Fragment next = required(fragments, children.get(index));
            for (Exit accepted : currentAccepted) {
                state.edge(accepted.source, next.entry, "ACCEPTED", Channel.ACCEPTED);
            }
            currentAccepted = channelExits(next, Channel.ACCEPTED);
            result.addExcept(next, Channel.ACCEPTED);
        }        for (Exit accepted : currentAccepted) {
            result.add(accepted);
        }
        return result;
    }

    private static Fragment route(NodeDescription node,
                                  IdentityHashMap<NodeDescription, Fragment> fragments,
                                  RenderState state) {
        String id = state.nextId();
        String otherwise = node.otherwise() == null ? "no-match=SKIPPED" : "otherwise=branch";
        state.node(id, "{", "}", metadata(node) + " | cases=" + node.routeCases().size()
                + " | " + otherwise);
        Fragment result = new Fragment(id);
        if (node.children().isEmpty()) {
            result.add(id, Channel.SKIPPED);
            return result;
        }

        Fragment selector = required(fragments, node.children().get(0));
        state.edge(id, selector.entry, "selector", null);
        result.addExcept(selector, Channel.ACCEPTED);

        for (int caseIndex = 0; caseIndex < node.routeCases().size(); caseIndex++) {
            Fragment branch = required(fragments, node.routeCases().get(caseIndex).branch());
            String key = stableConstant(node.routeCases().get(caseIndex).key());
            for (Exit accepted : channelExits(selector, Channel.ACCEPTED)) {
                state.edge(accepted.source, branch.entry, "ACCEPTED | case=" + key,
                        Channel.ACCEPTED);
            }
            result.addAll(branch);
        }

        if (node.otherwise() != null) {
            Fragment otherwiseBranch = required(fragments, node.otherwise());
            for (Exit accepted : channelExits(selector, Channel.ACCEPTED)) {
                state.edge(accepted.source, otherwiseBranch.entry, "ACCEPTED | otherwise",
                        Channel.ACCEPTED);
            }
            result.addAll(otherwiseBranch);
        } else {
            String noMatch = state.nextId();
            state.node(noMatch, "([", "])", "NO MATCH | SKIPPED");
            for (Exit accepted : channelExits(selector, Channel.ACCEPTED)) {
                state.edge(accepted.source, noMatch, "ACCEPTED | no match", Channel.SKIPPED);
            }
            result.add(noMatch, Channel.SKIPPED);
        }
        return result;
    }

    private static Fragment fallback(NodeDescription node,
                                     IdentityHashMap<NodeDescription, Fragment> fragments,
                                     RenderState state) {
        String id = state.nextId();
        Channel trigger = "FAILED".equals(node.trigger()) ? Channel.FAILED : Channel.SKIPPED;
        String mode = trigger == Channel.FAILED ? "recoverWith" : "firstApplicable";
        state.node(id, "{", "}", metadata(node) + " | " + mode + " | trigger=" + trigger);
        Fragment result = new Fragment(id);
        List<NodeDescription> branches = node.children();
        if (branches.isEmpty()) {
            result.add(id, trigger);
            return result;
        }

        Fragment first = required(fragments, branches.get(0));
        state.edge(id, first.entry, "branch=0", null);
        for (int index = 0; index < branches.size(); index++) {
            Fragment branch = required(fragments, branches.get(index));
            boolean hasNext = index + 1 < branches.size();
            for (Exit exit : allExits(branch)) {
                if (exit.channel == trigger && hasNext) {
                    Fragment next = required(fragments, branches.get(index + 1));
                    String action = trigger == Channel.FAILED ? "recover" : "next applicable";
                    state.edge(exit.source, next.entry, trigger + " | " + action,
                            trigger);
                } else {
                    result.add(exit);
                }
            }
        }
        return result;
    }

    private static Fragment parallel(NodeDescription node,
                                     IdentityHashMap<NodeDescription, Fragment> fragments,
                                     RenderState state) {
        String id = state.nextId();
        state.node(id, "{{", "}}", metadata(node) + " | branches="
                + node.parallelBranches().size());
        Fragment result = new Fragment(id);
        String waitAll = state.nextId();
        state.node(waitAll, "[[", "]]", "WAIT ALL | branches=" + node.parallelBranches().size());
        String cancel = state.nextId();
        state.node(cancel, "([", "])", "CANCEL | branches=" + node.parallelBranches().size());
        String suspendedNode = null;

        for (int index = 0; index < node.parallelBranches().size(); index++) {
            String token = display(node.parallelBranches().get(index).name());
            Fragment branch = required(fragments, node.parallelBranches().get(index).branch());
            String done = state.nextId();
            state.node(done, "([", "])", "BRANCH COMPLETE | token=" + token);
            state.edge(id, branch.entry, "branch=" + token, null);
            for (Exit exit : allExits(branch)) {
                if (exit.channel == Channel.CANCELLED) {
                    state.edge(exit.source, cancel, exit.channel.name(), exit.channel);
                } else if (exit.channel == Channel.SUSPENDED) {
                    if (suspendedNode == null) {
                        suspendedNode = state.nextId();
                        state.node(suspendedNode, "([", "])",
                                "SUSPENDED | branches=" + node.parallelBranches().size());
                    }
                    state.edge(exit.source, suspendedNode, exit.channel.name(), exit.channel);
                } else {
                    state.edge(exit.source, done, exit.channel.name(), exit.channel);
                }
            }
            state.edge(done, waitAll, "wait-all", null);
        }

        String join = state.nextId();
        state.node(join, "[", "]", "JOIN | static outcome contract");
        state.edge(waitAll, join, "all branches complete", null);
        addBusinessOutcomes(result, join);
        state.edge(id, cancel, Channel.CANCELLED.name(), Channel.CANCELLED);
        result.add(cancel, Channel.CANCELLED);
        if (suspendedNode != null) {
            result.add(suspendedNode, Channel.SUSPENDED);
        }
        return result;
    }

    private static Fragment await(NodeDescription node, RenderState state) {
        String id = state.nextId();
        state.node(id, "[", "]", metadata(node) + " | resume=" + display(node.resumePoint()));
        String suspended = state.nextId();
        state.node(suspended, "([", "])", "SUSPENDED | resume=" + display(node.resumePoint()));
        String resumed = state.nextId();
        state.node(resumed, "([", "])", "RESUMED | resume=" + display(node.resumePoint()));
        state.edge(id, suspended, "SUSPENDED", Channel.SUSPENDED);
        state.edge(suspended, resumed, "resume signal", null);
        Fragment result = new Fragment(id);
        result.add(resumed, Channel.ACCEPTED);
        result.add(suspended, Channel.SUSPENDED);
        result.add(suspended, Channel.CANCELLED);
        return result;
    }

    private static Fragment control(NodeDescription node,
                                    IdentityHashMap<NodeDescription, Fragment> fragments,
                                    RenderState state) {
        String id = state.nextId();
        String kind = display(node.controlKind());
        state.node(id, "[", "]", metadata(node) + " | control=" + kind
                + " | config=" + configurationSummary(node.configuration()) + binding(node));
        Fragment result = new Fragment(id);
        if (node.children().isEmpty()) {
            addBusinessOutcomes(result, id);
            return result;
        }

        Fragment body = required(fragments, node.children().get(0));
        state.edge(id, body.entry, "proceed", null);
        result.addAll(body);
        if ("RETRY".equals(node.controlKind())) {
            for (Exit failed : channelExits(body, Channel.FAILED)) {
                state.edge(failed.source, body.entry, "FAILED | retry while configured",
                        Channel.FAILED);
            }
        }
        if ("POLICY".equals(node.controlKind())
                || "PERSISTENT_POLICY".equals(node.controlKind())) {
            result.add(id, Channel.REJECTED);
            result.add(id, Channel.FAILED);
        } else if ("TIMEOUT".equals(node.controlKind())) {
            result.add(id, Channel.FAILED);
        }
        result.add(id, Channel.CANCELLED);
        return result;
    }

    private static Fragment complete(NodeDescription node, RenderState state) {
        String id = state.nextId();
        String completion = node.identity() ? "IDENTITY" : node.outcome().kind().name();
        state.node(id, "([", "])", metadata(node) + " | complete=" + completion);
        Fragment result = new Fragment(id);
        result.add(id, node.identity() ? Channel.ACCEPTED : Channel.valueOf(completion));
        return result;
    }

    private static void appendTerminals(RenderState state) {
        state.node("terminal_accepted", "([", "])", "COMPLETED | ACCEPTED");
        state.node("terminal_rejected", "([", "])", "COMPLETED | REJECTED");
        state.node("terminal_skipped", "([", "])", "COMPLETED | SKIPPED");
        state.node("terminal_failed", "([", "])", "COMPLETED | FAILED");
        state.node("terminal_suspended", "([", "])", "SUSPENDED");
        state.node("terminal_cancelled", "([", "])", "CANCELLED");
    }

    private static void appendStyles(StringBuilder output) {
        output.append("    classDef accepted fill:#dcfce7,stroke:#166534,color:#14532d\n")
                .append("    classDef rejected fill:#ffedd5,stroke:#c2410c,color:#7c2d12\n")
                .append("    classDef skipped fill:#f3f4f6,stroke:#4b5563,color:#1f2937\n")
                .append("    classDef failed fill:#fee2e2,stroke:#b91c1c,color:#7f1d1d\n")
                .append("    classDef suspended fill:#dbeafe,stroke:#1d4ed8,color:#1e3a8a\n")
                .append("    classDef cancelled fill:#e5e7eb,stroke:#111827,color:#111827\n")
                .append("    class terminal_accepted accepted\n")
                .append("    class terminal_rejected rejected\n")
                .append("    class terminal_skipped skipped\n")
                .append("    class terminal_failed failed\n")
                .append("    class terminal_suspended suspended\n")
                .append("    class terminal_cancelled cancelled\n");
    }

    private static void addBusinessOutcomes(Fragment fragment, String source) {
        fragment.add(source, Channel.ACCEPTED);
        fragment.add(source, Channel.REJECTED);
        fragment.add(source, Channel.SKIPPED);
        fragment.add(source, Channel.FAILED);
    }

    private static void forEachExit(Exits exits, ExitVisitor visitor) {
        if (exits == Exits.EMPTY) {
            return;
        }
        Deque<Exits> stack = new ArrayDeque<Exits>();
        stack.push(exits);
        while (!stack.isEmpty()) {
            Exits node = stack.pop();
            if (node instanceof SingleExit) {
                visitor.visit(((SingleExit) node).exit);
            } else if (node instanceof ConcatExits) {
                ConcatExits concat = (ConcatExits) node;
                stack.push(concat.right);
                stack.push(concat.left);
            }
        }
    }

    private static List<Exit> allExits(Fragment fragment) {
        final List<Exit> collected = new ArrayList<Exit>();
        forEachExit(fragment.exits, new ExitVisitor() {
            @Override
            public void visit(Exit exit) {
                collected.add(exit);
            }
        });
        return collected;
    }

    private static List<Exit> channelExits(final Fragment fragment, final Channel channel) {
        final List<Exit> matches = new ArrayList<Exit>();
        forEachExit(fragment.exits, new ExitVisitor() {
            @Override
            public void visit(Exit exit) {
                if (exit.channel == channel) {
                    matches.add(exit);
                }
            }
        });
        return matches;
    }

    private static Fragment required(IdentityHashMap<NodeDescription, Fragment> fragments,
                                     NodeDescription node) {
        Fragment fragment = fragments.get(node);
        if (fragment == null) {
            throw new IllegalStateException("Missing rendered child description at " + node.path());
        }
        return fragment;
    }

    private static String terminal(Channel channel) {
        return "terminal_" + channel.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String metadata(NodeDescription node) {
        return node.kind().name() + " | path=" + display(node.path())
                + " | label=" + (node.label().isPresent()
                ? display(node.label().get()) : "<none>");
    }

    private static String binding(NodeDescription node) {
        if (!node.binding().isPresent()) {
            return "";
        }
        BindingDescriptor binding = node.binding().get();
        String contract = binding.contractClass().isPresent()
                ? binding.contractClass().get().getName() : "<unresolved>";
        String qualifier = binding.qualifier().isPresent()
                ? display(binding.qualifier().get()) : "<none>";
        return " | binding=" + display(binding.kind()) + " contract=" + contract
                + " qualifier=" + qualifier;
    }

    /**
     * 稳定配置摘要：只读取 final 配置类（Retry / Duration）的字段，
     * 绝不调用任意 configuration 的 toString。
     */
    private static String configurationSummary(Object configuration) {
        if (configuration instanceof Retry) {
            Retry retry = (Retry) configuration;
            return "maxAttempts=" + retry.maxAttempts()
                    + ",backoff=" + durationSummary(retry.backoff());
        }
        if (configuration instanceof Duration) {
            return "timeout=" + durationSummary((Duration) configuration);
        }
        return "<none>";
    }

    private static String durationSummary(Duration duration) {
        return duration.getSeconds() + "s" + duration.getNano() + "ns";
    }

    /**
     * 稳定路由键渲染：仅对不可子类化且 toString 确定的精确类型输出值，
     * 其余一律输出固定占位符，不暴露任何类名（含 lambda/synthetic 类名）。
     */
    private static String stableConstant(Object value) {
        Class<?> type = value.getClass();
        if (type == String.class) {
            return display((String) value);
        }
        if (type == Integer.class || type == Long.class || type == Short.class
                || type == Byte.class || type == Character.class || type == Boolean.class
                || type == Float.class || type == Double.class) {
            return String.valueOf(value);
        }
        if (type == BigInteger.class || type == BigDecimal.class) {
            return value.toString();
        }
        if (value instanceof Enum<?>) {
            Enum<?> enumValue = (Enum<?>) value;
            return enumValue.getDeclaringClass().getName() + "." + enumValue.name();
        }
        if (type == Class.class) {
            Class<?> classValue = (Class<?>) value;
            return classValue.isSynthetic() ? "<opaque>" : classValue.getName();
        }
        return "<opaque>";
    }

    private static String display(String value) {
        return value == null ? "<unnamed>" : value;
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\': escaped.append("&#92;"); break;
                case '"': escaped.append("&quot;"); break;
                case '\n': escaped.append("<br/>"); break;
                case '\r': break;
                case '|': escaped.append("&#124;"); break;
                case '&': escaped.append("&amp;"); break;
                case '<': escaped.append("&lt;"); break;
                case '>': escaped.append("&gt;"); break;
                case '[': escaped.append("&#91;"); break;
                case ']': escaped.append("&#93;"); break;
                case '{': escaped.append("&#123;"); break;
                case '}': escaped.append("&#125;"); break;
                case '(': escaped.append("&#40;"); break;
                case ')': escaped.append("&#41;"); break;
                case '`': escaped.append("&#96;"); break;
                default: escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
