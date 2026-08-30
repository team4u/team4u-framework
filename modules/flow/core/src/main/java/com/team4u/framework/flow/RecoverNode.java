package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 失败恢复节点实现（将此前技术失败转换为最终成功、停止或新的失败）。
 *
 * @author jay.wu
 */
final class RecoverNode {

    private final String id;
    private final String path;
    private final String address;
    private final Recovery<Object, Object> recovery;
    private final Recovery.Contextual<Object, Object> contextualRecovery;

    @SuppressWarnings("unchecked")
    RecoverNode(String id, String path, String address,
                Recovery<?, ?> recovery,
                Recovery.Contextual<?, ?> contextualRecovery) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = path != null ? path : id;
        this.address = address != null ? address : id;
        this.recovery = (Recovery<Object, Object>) recovery;
        this.contextualRecovery = (Recovery.Contextual<Object, Object>) contextualRecovery;
    }

    public String id() {
        return id;
    }

    public String path() {
        return path;
    }

    public String address() {
        return address;
    }

    public NodeKind kind() {
        return NodeKind.RECOVER;
    }

    public FlowResult<Object> execute(ExecutionContext context, Object scopeInput, FailureContext failure) {
        long startNanos = System.nanoTime();
        context.notifyNodeStarted(id, path, NodeKind.RECOVER);

        StepContext stepContext = contextualRecovery != null ? context.createStepContext(id, path, address) : null;
        String invocationId = stepContext != null ? stepContext.invocationId() : null;

        try {
            FlowResult<Object> result;
            if (contextualRecovery != null) {
                result = contextualRecovery.recover(stepContext, scopeInput, failure);
            } else {
                result = recovery.recover(scopeInput, failure);
            }
            if (result == null) {
                throw new IllegalStateException("Recover [" + id + "] returned null FlowResult");
            }
            long duration = System.nanoTime() - startNanos;
            if (context.isTraceEnabled()) {
                context.traceCollector().recordLeaf(id, path, invocationId, NodeKind.RECOVER,
                        result.kind(), duration, null,
                        result.isStopped() ? result.stopReason() : null,
                        result.isFailed() ? result.failure() : null);
            }
            context.notifyNodeCompleted(id, path, NodeKind.RECOVER, result.kind(), duration,
                    result.isStopped() ? result.stopReason() : null,
                    result.isFailed() ? result.failure() : null);
            return result;
        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            }
            // Add original exception to suppressed
            t.addSuppressed(failure.cause());
            long duration = System.nanoTime() - startNanos;
            FailureContext newFailure = new FailureContext(id, path, t);
            if (context.isTraceEnabled()) {
                context.traceCollector().recordLeaf(id, path, invocationId, NodeKind.RECOVER,
                        FlowResult.Kind.FAILED, duration, null, null, newFailure);
            }
            context.notifyNodeCompleted(id, path, NodeKind.RECOVER, FlowResult.Kind.FAILED, duration, null, newFailure);
            return FlowResult.failed(newFailure);
        }
    }

    public NodeDescription describe() {
        return new NodeDescription(id, path, address, NodeKind.RECOVER, null, null, null, false, null, null, null, null);
    }

    public <R> R project(Flow.Projection<R> projection, R bodyProjection) {
        Flow.RecoverInfo info = new Flow.RecoverInfo(id, path, address, contextualRecovery != null);
        return projection.projectRecover(info, bodyProjection, recovery, contextualRecovery);
    }
}
