package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 流程失败上下文：保留失败节点与原始异常。
 *
 * @author jay.wu
 */
public final class FailureContext {

    private final String nodeId;
    private final String nodePath;
    private final Throwable cause;

    public FailureContext(String nodeId, String nodePath, Throwable cause) {
        if (nodeId == null || nodeId.trim().isEmpty()) {
            throw new IllegalArgumentException("FailureContext nodeId must not be null or blank");
        }
        if (cause == null) {
            throw new IllegalArgumentException("FailureContext cause must not be null");
        }
        this.nodeId = nodeId;
        this.nodePath = nodePath != null ? nodePath : nodeId;
        this.cause = cause;
    }

    public String nodeId() {
        return nodeId;
    }

    public String nodePath() {
        return nodePath;
    }

    public Throwable cause() {
        return cause;
    }

    public Exception exception() {
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return new RuntimeException(cause);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FailureContext)) return false;
        FailureContext that = (FailureContext) o;
        return Objects.equals(nodeId, that.nodeId) &&
                Objects.equals(nodePath, that.nodePath) &&
                Objects.equals(cause, that.cause);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, nodePath, cause);
    }

    @Override
    public String toString() {
        return "FailureContext{nodeId='" + nodeId + "', nodePath='" + nodePath + "', cause=" + cause + '}';
    }
}
