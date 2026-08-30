package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 默认不可变流程实现。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author jay.wu
 */
final class DefaultFlow<I, O> implements Flow<I, O> {

    private final String id;
    private final SequenceNode rootNode;

    DefaultFlow(String id, SequenceNode rootNode) {
        this.id = Objects.requireNonNull(id, "flowId must not be null");
        this.rootNode = Objects.requireNonNull(rootNode, "rootNode must not be null");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public O call(I input) {
        FlowExecution<O> execution = run(input, RunOptions.defaults());
        FlowResult<O> result = execution.result();
        if (result.isSucceeded()) {
            return result.value();
        }
        throw new FlowRunException(result);
    }

    @Override
    public FlowExecution<O> run(I input, RunOptions options) {
        if (input == null) {
            throw new IllegalArgumentException("Flow input must not be null");
        }
        if (options == null) {
            options = RunOptions.defaults();
        }

        ExecutionContext context = new ExecutionContext(id, options.executionId(), options.isTrace(), options.observer());
        context.notifyFlowStarted();

        long startNanos = System.nanoTime();
        FlowResult<Object> rawResult;
        try {
            rawResult = rootNode.execute(context, input);
        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            rawResult = FlowResult.failed(rootNode.id(), rootNode.path(), t);
        }

        if (rawResult.isFailed() && rawResult.failure().cause() instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }

        long duration = System.nanoTime() - startNanos;
        context.notifyFlowCompleted(rawResult.kind(), duration,
                rawResult.isStopped() ? rawResult.stopReason() : null,
                rawResult.isFailed() ? rawResult.failure() : null);

        @SuppressWarnings("unchecked")
        FlowResult<O> finalResult = (FlowResult<O>) rawResult;
        return new FlowExecution<>(id, context.executionId(), finalResult, context.buildTrace());
    }

    @Override
    public FlowDescription describe() {
        return new FlowDescription(id, rootNode.describeChildren());
    }

    @Override
    public <R> R project(Projection<R> projection) {
        return rootNode.project(projection);
    }

    SequenceNode rootNode() {
        return rootNode;
    }

    @Override
    public String toString() {
        return "Flow{id='" + id + "'}";
    }
}
