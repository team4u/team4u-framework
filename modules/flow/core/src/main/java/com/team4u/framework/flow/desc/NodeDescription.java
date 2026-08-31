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
            default:
                throw new IllegalStateException("Unknown node kind: " + kind);
        }
    }

    @Override
    public String toString() {
        return "NodeDescription[path=" + path + ", kind=" + kind + ", label=" + label + "]";
    }
}

