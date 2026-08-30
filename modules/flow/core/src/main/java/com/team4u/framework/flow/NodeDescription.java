package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 流程节点的只读结构描述。
 */
public final class NodeDescription {
    private final String path;
    private final Optional<String> label;
    private final NodeDescriptor.Kind kind;
    private final Optional<BindingDescriptor> binding;
    private final List<NodeDescription> children;
    private final String scopeName;
    private final String trigger;
    private final List<RouteCaseDescription> routeCases;
    private final NodeDescription otherwise;
    private final List<ParallelBranchDescription> parallelBranches;
    private final String resumePoint;
    private final String controlKind;
    private final Object configuration;
    private final Outcome<?> outcome;
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

    public String path() {
        return path;
    }

    public Optional<String> label() {
        return label;
    }

    public NodeDescriptor.Kind kind() {
        return kind;
    }

    public Optional<BindingDescriptor> binding() {
        return binding;
    }

    public List<NodeDescription> children() {
        return children;
    }

    public String scopeName() {
        return scopeName;
    }

    public String trigger() {
        return trigger;
    }

    public List<RouteCaseDescription> routeCases() {
        return routeCases;
    }

    public NodeDescription otherwise() {
        return otherwise;
    }

    public List<ParallelBranchDescription> parallelBranches() {
        return parallelBranches;
    }

    public String resumePoint() {
        return resumePoint;
    }

    public String controlKind() {
        return controlKind;
    }

    public Object configuration() {
        return configuration;
    }

    public Outcome<?> outcome() {
        return outcome;
    }

    public boolean identity() {
        return identity;
    }

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
