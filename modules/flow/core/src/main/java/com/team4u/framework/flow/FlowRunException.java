package com.team4u.framework.flow;

/**
 * 由 {@link Flow#call(Object)} 在流程未成功（STOPPED 或 FAILED）时抛出的运行时异常。
 *
 * @author jay.wu
 */
public class FlowRunException extends RuntimeException {

    private final FlowResult<?> result;

    public FlowRunException(FlowResult<?> result) {
        super(buildMessage(result), result != null && result.isFailed() ? result.failure().cause() : null);
        this.result = result;
    }

    public FlowRunException(String message, FlowResult<?> result) {
        super(message, result != null && result.isFailed() ? result.failure().cause() : null);
        this.result = result;
    }

    public FlowResult.Kind kind() {
        return result != null ? result.kind() : FlowResult.Kind.FAILED;
    }

    public StopReason stopReason() {
        return result != null && result.isStopped() ? result.stopReason() : null;
    }

    public String nodeId() {
        return result != null && result.isFailed() ? result.failure().nodeId() : null;
    }

    public String nodePath() {
        return result != null && result.isFailed() ? result.failure().nodePath() : null;
    }

    public FlowResult<?> result() {
        return result;
    }

    private static String buildMessage(FlowResult<?> result) {
        if (result == null) {
            return "Flow execution ended without result";
        }
        if (result.isStopped()) {
            return "Flow stopped with reason: " + result.stopReason();
        }
        if (result.isFailed()) {
            FailureContext failure = result.failure();
            return "Flow failed at node [" + failure.nodeId() + "]: " + failure.cause().getMessage();
        }
        return "Flow execution ended with " + result.kind();
    }
}
