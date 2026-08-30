package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 子流程节点实现（将另一个 Flow 作为子流程组合并保留轨迹层级）。
 *
 * @author jay.wu
 */
final class SubflowNode implements FlowNode {

    private final String id;
    private final String path;
    private final String address;
    private final Flow<Object, Object> subflow;

    @SuppressWarnings("unchecked")
    SubflowNode(String id, String path, String address, Flow<?, ?> subflow) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = path != null ? path : id;
        this.address = address != null ? address : id;
        this.subflow = (Flow<Object, Object>) Objects.requireNonNull(subflow, "subflow must not be null");
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
        return NodeKind.SUBFLOW;
    }

    @Override
    public FlowResult<Object> execute(ExecutionContext context, Object input) throws Exception {
        long startNanos = System.nanoTime();
        String effectivePath = context.qualifyPath(path);
        String effectiveAddress = context.qualifyAddress(address);

        context.notifyNodeStarted(id, effectivePath, NodeKind.SUBFLOW);

        if (context.isTraceEnabled()) {
            context.traceCollector().enterScope(id, effectivePath, null, NodeKind.SUBFLOW, null);
        }

        ExecutionContext childContext = context.childContext(path, address);
        FlowResult<Object> result;
        try {
            if (subflow instanceof DefaultFlow) {
                result = ((DefaultFlow<Object, Object>) subflow).rootNode().execute(childContext, input);
            } else {
                FlowExecution<Object> execution = subflow.run(input, RunOptions.builder()
                        .executionId(context.executionId())
                        .trace(context.isTraceEnabled())
                        .observer(context.observer())
                        .build());
                result = execution.result();
            }
        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            FailureContext failure = new FailureContext(id, effectivePath, t);
            result = FlowResult.failed(failure);
        }

        if (context.isTraceEnabled()) {
            context.traceCollector().exitScope(result.kind(),
                    result.isStopped() ? result.stopReason() : null,
                    result.isFailed() ? result.failure() : null);
        }

        long duration = System.nanoTime() - startNanos;
        context.notifyNodeCompleted(id, effectivePath, NodeKind.SUBFLOW, result.kind(), duration,
                result.isStopped() ? result.stopReason() : null,
                result.isFailed() ? result.failure() : null);

        return result;
    }

    @Override
    public NodeDescription describe() {
        return new NodeDescription(id, path, address, NodeKind.SUBFLOW, null, null, null, false,
                subflow.describe(), null, null, null);
    }

    @Override
    public <R> R project(Flow.Projection<R> projection) {
        Flow.SubflowInfo info = new Flow.SubflowInfo(id, path, address, subflow.id());
        R subflowProj = subflow.project(projection);
        return projection.projectSubflow(info, subflow, subflowProj);
    }
}
