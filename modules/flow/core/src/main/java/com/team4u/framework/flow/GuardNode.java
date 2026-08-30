package com.team4u.framework.flow;

import java.util.Objects;
import java.util.function.Function;

/**
 * 守卫节点实现（条件不满足时惰性生成原因并产生业务 STOPPED）。
 *
 * @author jay.wu
 */
final class GuardNode implements FlowNode {

    private final String id;
    private final String path;
    private final String address;
    private final Condition<Object> condition;
    private final Function<Object, StopReason> reasonFactory;

    @SuppressWarnings("unchecked")
    GuardNode(String id, String path, String address,
              Condition<?> condition,
              Function<?, StopReason> reasonFactory) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = path != null ? path : id;
        this.address = address != null ? address : id;
        this.condition = (Condition<Object>) Objects.requireNonNull(condition, "condition must not be null");
        this.reasonFactory = (Function<Object, StopReason>) Objects.requireNonNull(reasonFactory, "reasonFactory must not be null");
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
        return NodeKind.GUARD;
    }

    @Override
    public FlowResult<Object> execute(ExecutionContext context, Object input) {
        long startNanos = System.nanoTime();
        String effectivePath = context.qualifyPath(path);

        context.notifyNodeStarted(id, effectivePath, NodeKind.GUARD);

        try {
            boolean passed = condition.test(input);
            long duration = System.nanoTime() - startNanos;
            if (passed) {
                if (context.isTraceEnabled()) {
                    context.traceCollector().recordLeaf(id, effectivePath, null, NodeKind.GUARD,
                            FlowResult.Kind.SUCCEEDED, duration, null, null, null);
                }
                context.notifyNodeCompleted(id, effectivePath, NodeKind.GUARD, FlowResult.Kind.SUCCEEDED, duration, null, null);
                return FlowResult.succeeded(input);
            } else {
                StopReason reason = reasonFactory.apply(input);
                if (reason == null) {
                    throw new IllegalStateException("Guard reasonFactory returned null StopReason for node [" + id + "]");
                }
                if (context.isTraceEnabled()) {
                    context.traceCollector().recordLeaf(id, effectivePath, null, NodeKind.GUARD,
                            FlowResult.Kind.STOPPED, duration, null, reason, null);
                }
                context.notifyNodeCompleted(id, effectivePath, NodeKind.GUARD, FlowResult.Kind.STOPPED, duration, reason, null);
                return FlowResult.stopped(reason);
            }
        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long duration = System.nanoTime() - startNanos;
            FailureContext failure = new FailureContext(id, effectivePath, t);
            if (context.isTraceEnabled()) {
                context.traceCollector().recordLeaf(id, effectivePath, null, NodeKind.GUARD,
                        FlowResult.Kind.FAILED, duration, null, null, failure);
            }
            context.notifyNodeCompleted(id, effectivePath, NodeKind.GUARD, FlowResult.Kind.FAILED, duration, null, failure);
            return FlowResult.failed(failure);
        }
    }

    @Override
    public NodeDescription describe() {
        return new NodeDescription(id, path, address, NodeKind.GUARD, null, null, null, false, null, null, null, null);
    }

    @Override
    public <R> R project(Flow.Projection<R> projection) {
        Flow.GuardInfo info = new Flow.GuardInfo(id, path, address);
        return projection.projectGuard(info, condition, reasonFactory);
    }
}
