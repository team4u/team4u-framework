package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 终态清理节点实现（无论成功、停止或失败均执行一次清理动作）。
 *
 * @author jay.wu
 */
final class EnsureNode {

    private final String id;
    private final String path;
    private final String address;
    private final CompletionAction<Object, Object> completionAction;
    private final CompletionAction.Contextual<Object, Object> contextualCompletionAction;

    @SuppressWarnings("unchecked")
    EnsureNode(String id, String path, String address,
               CompletionAction<?, ?> completionAction,
               CompletionAction.Contextual<?, ?> contextualCompletionAction) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = path != null ? path : id;
        this.address = address != null ? address : id;
        this.completionAction = (CompletionAction<Object, Object>) completionAction;
        this.contextualCompletionAction = (CompletionAction.Contextual<Object, Object>) contextualCompletionAction;
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
        return NodeKind.ENSURE;
    }

    public FlowResult<Object> execute(ExecutionContext context, Object scopeInput, FlowResult<Object> currentResult) {
        long startNanos = System.nanoTime();
        context.notifyNodeStarted(id, path, NodeKind.ENSURE);

        StepContext stepContext = contextualCompletionAction != null ? context.createStepContext(id, path, address) : null;
        String invocationId = stepContext != null ? stepContext.invocationId() : null;

        CompletionContext<Object> completionContext = new CompletionContext<>(currentResult);
        try {
            if (contextualCompletionAction != null) {
                contextualCompletionAction.onComplete(stepContext, scopeInput, completionContext);
            } else {
                completionAction.onComplete(scopeInput, completionContext);
            }
            long duration = System.nanoTime() - startNanos;
            if (context.isTraceEnabled()) {
                context.traceCollector().recordLeaf(id, path, invocationId, NodeKind.ENSURE,
                        FlowResult.Kind.SUCCEEDED, duration, null, null, null);
            }
            context.notifyNodeCompleted(id, path, NodeKind.ENSURE, FlowResult.Kind.SUCCEEDED, duration, null, null);
            return currentResult;
        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            }
            long duration = System.nanoTime() - startNanos;
            if (currentResult.isFailed()) {
                Throwable origCause = currentResult.failure().cause();
                origCause.addSuppressed(t);
                FailureContext ensureFail = new FailureContext(id, path, t);
                if (context.isTraceEnabled()) {
                    context.traceCollector().recordLeaf(id, path, invocationId, NodeKind.ENSURE,
                            FlowResult.Kind.FAILED, duration, null, null, ensureFail);
                }
                context.notifyNodeCompleted(id, path, NodeKind.ENSURE, FlowResult.Kind.FAILED, duration, null, ensureFail);
                return currentResult;
            } else {
                FailureContext ensureFail = new FailureContext(id, path, t);
                if (context.isTraceEnabled()) {
                    context.traceCollector().recordLeaf(id, path, invocationId, NodeKind.ENSURE,
                            FlowResult.Kind.FAILED, duration, null, null, ensureFail);
                }
                context.notifyNodeCompleted(id, path, NodeKind.ENSURE, FlowResult.Kind.FAILED, duration, null, ensureFail);
                return FlowResult.failed(ensureFail);
            }
        }
    }

    public NodeDescription describe() {
        return new NodeDescription(id, path, address, NodeKind.ENSURE, null, null, null, false, null, null, null, null);
    }

    public <R> R project(Flow.Projection<R> projection, R bodyProjection) {
        Flow.EnsureInfo info = new Flow.EnsureInfo(id, path, address, contextualCompletionAction != null);
        return projection.projectEnsure(info, bodyProjection, completionAction, contextualCompletionAction);
    }
}
