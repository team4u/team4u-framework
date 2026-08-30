package com.team4u.framework.flow.durable;

import java.util.Objects;

/**
 * 可持久化的失败摘要（不保存不可序列化或含环境上下文的 Throwable 对象）。
 *
 * @author jay.wu
 */
public final class DurableFailure {

    private final String nodeId;
    private final String nodePath;
    private final String errorType;
    private final String message;

    public DurableFailure(String nodeId, String nodePath, String errorType, String message) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.nodePath = nodePath != null ? nodePath : nodeId;
        this.errorType = errorType != null ? errorType : "java.lang.Exception";
        this.message = message != null ? message : "";
    }

    public String nodeId() {
        return nodeId;
    }

    public String nodePath() {
        return nodePath;
    }

    public String errorType() {
        return errorType;
    }

    public String message() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DurableFailure)) return false;
        DurableFailure that = (DurableFailure) o;
        return Objects.equals(nodeId, that.nodeId) &&
                Objects.equals(nodePath, that.nodePath) &&
                Objects.equals(errorType, that.errorType) &&
                Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, nodePath, errorType, message);
    }

    @Override
    public String toString() {
        return "DurableFailure{" + nodeId + "[" + errorType + "]: " + message + '}';
    }
}
