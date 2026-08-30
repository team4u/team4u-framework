package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 顺序节点序列容器（负责顺序推进、recover 捕获与 ensure 清理）。
 *
 * @author jay.wu
 */
final class SequenceNode implements FlowNode {

    private final String id;
    private final String path;
    private final String address;
    private final List<FlowNode> nodes;
    private final RecoverNode recoverNode;
    private final EnsureNode ensureNode;

    SequenceNode(String id, String path, String address,
                 List<FlowNode> nodes,
                 RecoverNode recoverNode,
                 EnsureNode ensureNode) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = path != null ? path : id;
        this.address = address != null ? address : id;
        this.nodes = nodes != null ? Collections.unmodifiableList(new ArrayList<>(nodes)) : Collections.emptyList();
        this.recoverNode = recoverNode;
        this.ensureNode = ensureNode;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String address() {
        return address;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.SEQUENCE;
    }

    List<FlowNode> nodes() {
        return nodes;
    }

    RecoverNode recoverNode() {
        return recoverNode;
    }

    EnsureNode ensureNode() {
        return ensureNode;
    }

    @Override
    public FlowResult<Object> execute(ExecutionContext context, Object input) throws Exception {
        Object currentInput = input;
        FlowResult<Object> result = null;

        for (FlowNode node : nodes) {
            if (Thread.currentThread().isInterrupted()) {
                InterruptedException ie = new InterruptedException("Thread was interrupted during flow execution");
                FailureContext fail = new FailureContext(node.id(), node.path(), ie);
                result = FlowResult.failed(fail);
                break;
            }

            FlowResult<Object> nodeResult = node.execute(context, currentInput);
            if (nodeResult.isSucceeded()) {
                currentInput = nodeResult.value();
                result = nodeResult;
            } else {
                result = nodeResult;
                break;
            }
        }

        if (result == null) {
            throw new IllegalStateException("Sequence execution ended without result");
        }

        if (result.isFailed() && recoverNode != null) {
            result = recoverNode.execute(context, input, result.failure());
        }

        if (ensureNode != null) {
            result = ensureNode.execute(context, input, result);
        }

        return result;
    }

    @Override
    public NodeDescription describe() {
        List<NodeDescription> childDescs = new ArrayList<>();
        for (FlowNode node : nodes) {
            childDescs.add(node.describe());
        }
        return new NodeDescription(id, path, address, NodeKind.SEQUENCE, null, null, null, false, null,
                recoverNode != null ? recoverNode.describe() : null,
                ensureNode != null ? ensureNode.describe() : null,
                childDescs);
    }

    List<NodeDescription> describeChildren() {
        List<NodeDescription> list = new ArrayList<>();
        for (FlowNode node : nodes) {
            list.add(node.describe());
        }
        if (recoverNode != null) {
            list.add(recoverNode.describe());
        }
        if (ensureNode != null) {
            list.add(ensureNode.describe());
        }
        return list;
    }

    @Override
    public <R> R project(Flow.Projection<R> projection) {
        List<R> childProjections = new ArrayList<>();
        for (FlowNode node : nodes) {
            childProjections.add(node.project(projection));
        }
        R body = projection.projectSequence(new Flow.SequenceInfo(id, path, address), childProjections);
        if (recoverNode != null) {
            body = recoverNode.project(projection, body);
        }
        if (ensureNode != null) {
            body = ensureNode.project(projection, body);
        }
        return body;
    }
}
