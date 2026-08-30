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
    public FlowResult<Object> execute(ExecutionContext context, Object input) {
        long startNanos = System.nanoTime();
        context.notifyNodeStarted(id, path, NodeKind.SUBFLOW);

        if (context.isTraceEnabled()) {
            context.traceCollector().enterScope(id, path, null, NodeKind.SUBFLOW, null);
        }

        RunOptions options = RunOptions.builder()
                .executionId(context.executionId())
                .trace(context.isTraceEnabled())
                .observer(null) // Observer already managed by parent context
                .build();

        FlowExecution<Object> execution = subflow.run(input, options);
        FlowResult<Object> result = execution.result();

        if (context.isTraceEnabled()) {
            if (execution.trace() != null) {
                context.traceCollector().recordEntries(execution.trace().entries());
            }
            context.traceCollector().exitScope(result.kind(),
                    result.isStopped() ? result.stopReason() : null,
                    result.isFailed() ? result.failure() : null);
        }

        long duration = System.nanoTime() - startNanos;
        context.notifyNodeCompleted(id, path, NodeKind.SUBFLOW, result.kind(), duration,
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
