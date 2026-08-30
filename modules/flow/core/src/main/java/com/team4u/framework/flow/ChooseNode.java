package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * 分支选择节点实现。
 *
 * @author jay.wu
 */
final class ChooseNode implements FlowNode {

    private final String id;
    private final String path;
    private final String address;
    private final Function<Object, Object> selector;
    private final Map<Object, Flow<Object, Object>> branches;
    private final Flow<Object, Object> otherwiseBranch;
    private final Function<Object, StopReason> otherwiseStopReason;

    @SuppressWarnings("unchecked")
    ChooseNode(String id, String path, String address,
               Function<?, ?> selector,
               Map<?, ? extends Flow<?, ?>> branches,
               Flow<?, ?> otherwiseBranch,
               Function<?, StopReason> otherwiseStopReason) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = path != null ? path : id;
        this.address = address != null ? address : id;
        this.selector = (Function<Object, Object>) Objects.requireNonNull(selector, "selector must not be null");

        Map<Object, Flow<Object, Object>> bMap = new LinkedHashMap<>();
        if (branches != null) {
            for (Map.Entry<?, ? extends Flow<?, ?>> entry : branches.entrySet()) {
                bMap.put(entry.getKey(), (Flow<Object, Object>) entry.getValue());
            }
        }
        this.branches = Collections.unmodifiableMap(bMap);
        this.otherwiseBranch = (Flow<Object, Object>) otherwiseBranch;
        this.otherwiseStopReason = (Function<Object, StopReason>) otherwiseStopReason;
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
        return NodeKind.CHOOSE;
    }

    @Override
    public FlowResult<Object> execute(ExecutionContext context, Object input) {
        long startNanos = System.nanoTime();
        context.notifyNodeStarted(id, path, NodeKind.CHOOSE);

        Object key;
        try {
            key = selector.apply(input);
            if (key == null) {
                throw new IllegalStateException("Choose selector returned null key for node [" + id + "]");
            }
        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            }
            long duration = System.nanoTime() - startNanos;
            FailureContext failure = new FailureContext(id, path, t);
            if (context.isTraceEnabled()) {
                context.traceCollector().recordLeaf(id, path, null, NodeKind.CHOOSE,
                        FlowResult.Kind.FAILED, duration, null, null, failure);
            }
            context.notifyNodeCompleted(id, path, NodeKind.CHOOSE, FlowResult.Kind.FAILED, duration, null, failure);
            return FlowResult.failed(failure);
        }

        Flow<Object, Object> branch = branches.get(key);
        if (branch != null) {
            return executeBranch(context, input, branch, String.valueOf(key), startNanos);
        }

        if (otherwiseBranch != null) {
            return executeBranch(context, input, otherwiseBranch, "otherwise", startNanos);
        }

        if (otherwiseStopReason != null) {
            try {
                StopReason reason = otherwiseStopReason.apply(input);
                if (reason == null) {
                    throw new IllegalStateException("Choose otherwiseStopReason returned null StopReason for node [" + id + "]");
                }
                long duration = System.nanoTime() - startNanos;
                if (context.isTraceEnabled()) {
                    context.traceCollector().recordLeaf(id, path, null, NodeKind.CHOOSE,
                            FlowResult.Kind.STOPPED, duration, String.valueOf(key), reason, null);
                }
                context.notifyNodeCompleted(id, path, NodeKind.CHOOSE, FlowResult.Kind.STOPPED, duration, reason, null);
                return FlowResult.stopped(reason);
            } catch (Throwable t) {
                if (t instanceof Error) {
                    throw (Error) t;
                }
                long duration = System.nanoTime() - startNanos;
                FailureContext failure = new FailureContext(id, path, t);
                if (context.isTraceEnabled()) {
                    context.traceCollector().recordLeaf(id, path, null, NodeKind.CHOOSE,
                            FlowResult.Kind.FAILED, duration, String.valueOf(key), null, failure);
                }
                context.notifyNodeCompleted(id, path, NodeKind.CHOOSE, FlowResult.Kind.FAILED, duration, null, failure);
                return FlowResult.failed(failure);
            }
        }

        // Branch not matched and no otherwise
        long duration = System.nanoTime() - startNanos;
        NoSuchElementException noMatchEx = new NoSuchElementException("No branch matched key [" + key + "] for choose node [" + id + "]");
        FailureContext failure = new FailureContext(id, path, noMatchEx);
        if (context.isTraceEnabled()) {
            context.traceCollector().recordLeaf(id, path, null, NodeKind.CHOOSE,
                    FlowResult.Kind.FAILED, duration, String.valueOf(key), null, failure);
        }
        context.notifyNodeCompleted(id, path, NodeKind.CHOOSE, FlowResult.Kind.FAILED, duration, null, failure);
        return FlowResult.failed(failure);
    }

    private FlowResult<Object> executeBranch(ExecutionContext context, Object input, Flow<Object, Object> branchFlow,
                                             String branchKey, long startNanos) {
        if (context.isTraceEnabled()) {
            context.traceCollector().enterScope(id, path, null, NodeKind.CHOOSE, branchKey);
        }

        RunOptions options = RunOptions.builder()
                .executionId(context.executionId())
                .trace(context.isTraceEnabled())
                .observer(null) // Observer already managed by parent context
                .build();

        FlowExecution<Object> execution = branchFlow.run(input, options);
        FlowResult<Object> result = execution.result();

        if (context.isTraceEnabled()) {
            // Incorporate branch execution trace into collector
            if (execution.trace() != null) {
                context.traceCollector().recordEntries(execution.trace().entries());
            }
            context.traceCollector().exitScope(result.kind(),
                    result.isStopped() ? result.stopReason() : null,
                    result.isFailed() ? result.failure() : null);
        }

        long duration = System.nanoTime() - startNanos;
        context.notifyNodeCompleted(id, path, NodeKind.CHOOSE, result.kind(), duration,
                result.isStopped() ? result.stopReason() : null,
                result.isFailed() ? result.failure() : null);
        return result;
    }

    @Override
    public NodeDescription describe() {
        List<String> keys = new ArrayList<>();
        Map<String, FlowDescription> branchDescMap = new LinkedHashMap<>();
        for (Map.Entry<Object, Flow<Object, Object>> entry : branches.entrySet()) {
            String kStr = String.valueOf(entry.getKey());
            keys.add(kStr);
            branchDescMap.put(kStr, entry.getValue().describe());
        }
        FlowDescription otherwiseDesc = otherwiseBranch != null ? otherwiseBranch.describe() : null;

        return new NodeDescription(id, path, address, NodeKind.CHOOSE, keys, branchDescMap,
                otherwiseDesc, otherwiseStopReason != null, null, null, null, null);
    }

    @Override
    public <R> R project(Flow.Projection<R> projection) {
        List<Object> keys = new ArrayList<>(branches.keySet());
        Flow.ChooseInfo<Object> info = new Flow.ChooseInfo<>(id, path, address, keys,
                otherwiseBranch != null, otherwiseStopReason != null);

        Map<Object, R> branchProjections = new LinkedHashMap<>();
        for (Map.Entry<Object, Flow<Object, Object>> entry : branches.entrySet()) {
            branchProjections.put(entry.getKey(), entry.getValue().project(projection));
        }
        R otherwiseProj = otherwiseBranch != null ? otherwiseBranch.project(projection) : null;

        return projection.projectChoose(info, selector, branchProjections, otherwiseProj, otherwiseStopReason);
    }
}
