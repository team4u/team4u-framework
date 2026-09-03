package com.team4u.framework.flow.desc;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.spi.BindingDescriptor;
import com.team4u.framework.flow.spi.NodeDescriptor;

/**
 * 流程节点的只读结构描述模型（AST 静态拓扑视图）。
 *
 * <p>以不可变纯数据结构反映 Flow AST 的完整结构，支持通过访问者模式（{@link FlowVisitor}）
 * 遍历各类型节点生成 Graphviz / Mermaid / 字符文本渲染输出。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class NodeDescription {
    /** 节点的绝对树路径表达式（如 {@code $}, {@code $.0}, {@code $.1.case[VIP]}）。 */
    private final String path;
    /** 可选的人类可读标签。 */
    private final Optional<String> label;
    /** 节点类型分类。 */
    private final NodeDescriptor.Kind kind;
    /** 绑定的组件描述符（针对 INVOKE / CONTROL 节点）。 */
    private final Optional<BindingDescriptor> binding;
    /** 子节点列表（针对 SEQUENCE / FALLBACK 节点）。 */
    private final List<NodeDescription> children;
    /** 作用域名称（针对具名 SEQUENCE Scope）。 */
    private final String scopeName;
    /** 降级触发条件（针对 FALLBACK 节点）。 */
    private final String trigger;
    /** 条件路由分支描述列表（针对 ROUTE 节点）。 */
    private final List<RouteCaseDescription> routeCases;
    /** 路由兜底分支（针对 ROUTE 节点）。 */
    private final NodeDescription otherwise;
    /** 并行分支描述列表（针对 PARALLEL 节点）。 */
    private final List<ParallelBranchDescription> parallelBranches;
    /** 挂起点标识名称（针对 AWAIT 节点）。 */
    private final String resumePoint;
    /** 控制种类（针对 CONTROL 节点，如 POLICY / PERSISTENT_POLICY / TIMEOUT）。 */
    private final String controlKind;
    /** 控制策略配置对象。 */
    private final Object configuration;
    /** 常量输出结果（针对 COMPLETE 节点）。 */
    private final Outcome<?> outcome;
    /** 是否为恒等透传节点（针对 COMPLETE 节点）。 */
    private final boolean identity;

    NodeDescription(String path, Optional<String> label, NodeDescriptor.Kind kind,
                    Optional<BindingDescriptor> binding, List<NodeDescription> children,
                    String scopeName, String trigger, List<RouteCaseDescription> routeCases,
                    NodeDescription otherwise, List<ParallelBranchDescription> parallelBranches,
                    String resumePoint, String controlKind, Object configuration,
                    Outcome<?> outcome, boolean identity) {
        this.path = Objects.requireNonNull(path, "path must not be null");
        this.label = Objects.requireNonNull(label, "label must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.children = children != null
                ? Collections.unmodifiableList(new ArrayList<NodeDescription>(children))
                : Collections.emptyList();
        this.scopeName = scopeName;
        this.trigger = trigger;
        this.routeCases = routeCases != null
                ? Collections.unmodifiableList(new ArrayList<RouteCaseDescription>(routeCases))
                : Collections.emptyList();
        this.otherwise = otherwise;
        this.parallelBranches = parallelBranches != null
                ? Collections.unmodifiableList(new ArrayList<ParallelBranchDescription>(parallelBranches))
                : Collections.emptyList();
        this.resumePoint = resumePoint;
        this.controlKind = controlKind;
        this.configuration = configuration;
        this.outcome = outcome;
        this.identity = identity;
    }

    /**
     * 构造 INVOKE（原子操作调用）节点描述。
     *
     * @param path    节点路径，不能为 null
     * @param label   可选标签，可为 null
     * @param binding 绑定描述符，可为 null（未解析绑定时）
     * @return INVOKE 节点描述
     */
    public static NodeDescription invoke(String path, String label, BindingDescriptor binding) {
        return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.INVOKE,
                Optional.ofNullable(binding), null, null, null, null, null, null,
                null, null, null, null, false);
    }

    /**
     * 构造 SEQUENCE（顺序流水线/具名作用域）节点描述。
     *
     * @param path      节点路径，不能为 null
     * @param label     可选标签，可为 null
     * @param children  子节点列表，可为 null（视为空）
     * @param scopeName 具名作用域名，可为 null
     * @return SEQUENCE 节点描述
     */
    public static NodeDescription sequence(String path, String label,
                                           List<NodeDescription> children, String scopeName) {
        return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.SEQUENCE,
                Optional.empty(), children, scopeName, null, null, null, null,
                null, null, null, null, false);
    }

    /**
     * 构造 ROUTE（动态条件路由）节点描述。
     *
     * @param path       节点路径，不能为 null
     * @param label      可选标签，可为 null
     * @param selector   选择器 INVOKE 子节点，不能为 null
     * @param cases      条件分支列表，可为 null（视为空）
     * @param otherwise  兑底分支，可为 null
     * @return ROUTE 节点描述（children = selector + 命中分支 + 兑底分支）
     */
    public static NodeDescription route(String path, String label, NodeDescription selector,
                                        List<RouteCaseDescription> cases, NodeDescription otherwise) {
        Objects.requireNonNull(selector, "selector must not be null");
        List<NodeDescription> children = new ArrayList<NodeDescription>();
        children.add(selector);
        List<RouteCaseDescription> safeCases = cases != null
                ? cases : Collections.<RouteCaseDescription>emptyList();
        for (RouteCaseDescription routeCase : safeCases) {
            children.add(routeCase.branch());
        }
        if (otherwise != null) {
            children.add(otherwise);
        }
        return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.ROUTE,
                Optional.empty(), children, null, null, safeCases, otherwise, null,
                null, null, null, null, false);
    }

    /**
     * 构造 FALLBACK（降级恢复）节点描述。
     *
     * @param path     节点路径，不能为 null
     * @param label    可选标签，可为 null
     * @param trigger  降级触发条件名（SKIPPED / FAILED），不能为 null
     * @param branches 候选分支列表，可为 null（视为空）
     * @return FALLBACK 节点描述
     */
    public static NodeDescription fallback(String path, String label, String trigger,
                                           List<NodeDescription> branches) {
        Objects.requireNonNull(trigger, "trigger must not be null");
        return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.FALLBACK,
                Optional.empty(), branches, null, trigger, null, null, null,
                null, null, null, null, false);
    }

    /**
     * 构造 PARALLEL（结构化并行）节点描述。
     *
     * @param path     节点路径，不能为 null
     * @param label    可选标签，可为 null
     * @param branches 并行分支描述列表，可为 null（视为空）
     * @return PARALLEL 节点描述（children 与分支声明顺序一致）
     */
    public static NodeDescription parallel(String path, String label,
                                           List<ParallelBranchDescription> branches) {
        List<ParallelBranchDescription> safeBranches = branches != null
                ? branches : Collections.<ParallelBranchDescription>emptyList();
        List<NodeDescription> children = new ArrayList<NodeDescription>(safeBranches.size());
        for (ParallelBranchDescription branch : safeBranches) {
            children.add(branch.branch());
        }
        return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.PARALLEL,
                Optional.empty(), children, null, null, null, null, safeBranches,
                null, null, null, null, false);
    }

    /**
     * 构造 AWAIT（异步挂起等待）节点描述。
     *
     * @param path        节点路径，不能为 null
     * @param label       可选标签，可为 null
     * @param resumePoint 挂起点名称，不能为 null
     * @return AWAIT 节点描述
     */
    public static NodeDescription await(String path, String label, String resumePoint) {
        Objects.requireNonNull(resumePoint, "resumePoint must not be null");
        return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.AWAIT,
                Optional.empty(), null, null, null, null, null, null,
                resumePoint, null, null, null, false);
    }

    /**
     * 构造 CONTROL（环绕治理控制）节点描述。
     *
     * @param path          节点路径，不能为 null
     * @param label         可选标签，可为 null
     * @param binding       绑定描述符，可为 null（如 TIMEOUT 无绑定）
     * @param body          被包裹的子节点，不能为 null
     * @param controlKind   控制种类名（POLICY / PERSISTENT_POLICY / TIMEOUT），不能为 null
     * @param configuration 控制策略配置对象，可为 null
     * @return CONTROL 节点描述
     */
    public static NodeDescription control(String path, String label, BindingDescriptor binding,
                                          NodeDescription body, String controlKind,
                                          Object configuration) {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(controlKind, "controlKind must not be null");
        return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.CONTROL,
                Optional.ofNullable(binding), Collections.singletonList(body), null, null, null,
                null, null, null, controlKind, configuration, null, false);
    }

    /**
     * 构造 COMPLETE（常量输出终态）节点描述。
     *
     * @param path    节点路径，不能为 null
     * @param label   可选标签，可为 null
     * @param outcome 常量输出结果，不能为 null
     * @return COMPLETE 节点描述
     */
    public static NodeDescription complete(String path, String label, Outcome<?> outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.COMPLETE,
                Optional.empty(), null, null, null, null, null, null,
                null, null, null, outcome, false);
    }

    /**
     * 构造 COMPLETE（恒等透传终态）节点描述。
     *
     * @param path  节点路径，不能为 null
     * @param label 可选标签，可为 null
     * @return 恒等透传 COMPLETE 节点描述
     */
    public static NodeDescription identityComplete(String path, String label) {
        return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.COMPLETE,
                Optional.empty(), null, null, null, null, null, null,
                null, null, null, null, true);
    }

    /**
     * 构造 ADAPTER（结构化子流适配）节点描述。
     *
     * @param path  节点路径，不能为 null
     * @param label 可选标签，可为 null
     * @param body  适配体子节点描述，不能为 null
     * @return ADAPTER 节点描述
     */
    public static NodeDescription adapter(String path, String label, NodeDescription body) {
        Objects.requireNonNull(body, "body must not be null");
        return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.ADAPTER,
                Optional.empty(), Collections.singletonList(body), null, null, null, null, null,
                null, null, null, null, false);
    }

    /**
     * 接受访问者遍历并导出视图结果。
     *
     * @param visitor 流程描述访问者，不能为 null
     * @param <R>     访问计算的返回类型
     * @return 访问者计算结果
     * @throws NullPointerException 当 {@code visitor} 为 null 时抛出
     */
    public <R> R accept(FlowVisitor<R> visitor) {
        Objects.requireNonNull(visitor, "visitor must not be null");
        switch (kind) {
            case INVOKE:
                return visitor.visitInvoke(this);
            case SEQUENCE:
                return visitor.visitSequence(this);
            case ROUTE:
                return visitor.visitRoute(this);
            case FALLBACK:
                return visitor.visitFallback(this);
            case PARALLEL:
                return visitor.visitParallel(this);
            case AWAIT:
                return visitor.visitAwait(this);
            case CONTROL:
                return visitor.visitControl(this);
            case COMPLETE:
                return visitor.visitComplete(this);
            case ADAPTER:
                return visitor.visitAdapter(this);
            default:
                throw new IllegalStateException("Unknown node kind: " + kind);
        }
    }

    @Override
    public String toString() {
        return "NodeDescription[path=" + path + ", kind=" + kind + ", label=" + label + "]";
    }
}

