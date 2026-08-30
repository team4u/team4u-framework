package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 流程节点的只读结构描述。
 *
 * @author jay.wu
 */
public final class NodeDescription {

    private final String id;
    private final String path;
    private final String address;
    private final NodeKind kind;
    private final List<String> branchKeys;
    private final Map<String, FlowDescription> branches;
    private final FlowDescription otherwiseBranch;
    private final boolean hasOtherwiseStop;
    private final FlowDescription subflow;
    private final NodeDescription recoverNode;
    private final NodeDescription ensureNode;
    private final List<NodeDescription> children;

    public NodeDescription(String id, String path, String address, NodeKind kind,
                           List<String> branchKeys, Map<String, FlowDescription> branches,
                           FlowDescription otherwiseBranch, boolean hasOtherwiseStop,
                           FlowDescription subflow, NodeDescription recoverNode,
                           NodeDescription ensureNode, List<NodeDescription> children) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = path != null ? path : id;
        this.address = address != null ? address : id;
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.branchKeys = branchKeys != null ? Collections.unmodifiableList(new ArrayList<>(branchKeys)) : Collections.emptyList();
        this.branches = branches != null ? Collections.unmodifiableMap(new LinkedHashMap<>(branches)) : Collections.emptyMap();
        this.otherwiseBranch = otherwiseBranch;
        this.hasOtherwiseStop = hasOtherwiseStop;
        this.subflow = subflow;
        this.recoverNode = recoverNode;
        this.ensureNode = ensureNode;
        this.children = children != null ? Collections.unmodifiableList(new ArrayList<>(children)) : Collections.emptyList();
    }

    public String id() { return id; }
    public String path() { return path; }
    public String address() { return address; }
    public NodeKind kind() { return kind; }
    public List<String> branchKeys() { return branchKeys; }
    public Map<String, FlowDescription> branches() { return branches; }
    public FlowDescription otherwiseBranch() { return otherwiseBranch; }
    public boolean hasOtherwiseStop() { return hasOtherwiseStop; }
    public FlowDescription subflow() { return subflow; }
    public NodeDescription recoverNode() { return recoverNode; }
    public NodeDescription ensureNode() { return ensureNode; }
    public List<NodeDescription> children() { return children; }

    @Override
    public String toString() {
        return "NodeDescription{id='" + id + "', kind=" + kind + '}';
    }
}
