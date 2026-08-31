package com.team4u.framework.flow;

import java.util.Objects;
import java.util.Optional;

/**
 * 单个执行节点的稳定元数据：flowId/version、executionId、节点 path 与可选 label。
 */
public final class Metadata {
    private final String flowId;
    private final int flowVersion;
    private final String executionId;
    private final String nodePath;
    private final Optional<String> label;

    public Metadata(String flowId, int flowVersion, String executionId,
                    String nodePath, Optional<String> label) {
        this.flowId = text(flowId, "flowId");
        if (flowVersion < 0) throw new IllegalArgumentException("flowVersion must not be negative");
        this.flowVersion = flowVersion;
        this.executionId = text(executionId, "executionId");
        this.nodePath = text(nodePath, "nodePath");
        Objects.requireNonNull(label, "label must not be null");
        this.label = label.map(value -> text(value, "label"));
    }

    public Metadata(String flowId, int flowVersion, String executionId, String nodePath) {
        this(flowId, flowVersion, executionId, nodePath, Optional.empty());
    }

    public String flowId() {
        return flowId;
    }

    public int flowVersion() {
        return flowVersion;
    }

    public String executionId() {
        return executionId;
    }

    public String nodePath() {
        return nodePath;
    }

    public Optional<String> label() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Metadata metadata = (Metadata) o;
        return flowVersion == metadata.flowVersion
                && flowId.equals(metadata.flowId)
                && executionId.equals(metadata.executionId)
                && nodePath.equals(metadata.nodePath)
                && label.equals(metadata.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(flowId, flowVersion, executionId, nodePath, label);
    }

    @Override
    public String toString() {
        return "Metadata[flowId=" + flowId + ", flowVersion=" + flowVersion
                + ", executionId=" + executionId + ", nodePath=" + nodePath
                + ", label=" + label + "]";
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
