package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 业务步骤节点实现。
 *
 * @author jay.wu
 */
final class StepNode implements FlowNode {

    private final String id;
    private final String path;
    private final String address;
    private final Step<Object, Object> step;
    private final Step.Contextual<Object, Object> contextualStep;
    private final List<StepInterceptor> interceptors;

    @SuppressWarnings("unchecked")
    StepNode(String id, String path, String address,
             Step<?, ?> step,
             Step.Contextual<?, ?> contextualStep,
             List<StepInterceptor> interceptors) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = path != null ? path : id;
        this.address = address != null ? address : id;
        this.step = (Step<Object, Object>) step;
        this.contextualStep = (Step.Contextual<Object, Object>) contextualStep;
        this.interceptors = interceptors != null ? Collections.unmodifiableList(new ArrayList<>(interceptors)) : Collections.emptyList();
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
        return NodeKind.STEP;
    }

    @Override
    public FlowResult<Object> execute(ExecutionContext context, Object input) {
        long startNanos = System.nanoTime();
        String effectivePath = context.qualifyPath(path);
        String effectiveAddress = context.qualifyAddress(address);

        context.notifyNodeStarted(id, effectivePath, NodeKind.STEP);

        StepContext stepContext = (contextualStep != null || !interceptors.isEmpty())
                ? context.createStepContext(id, path, address)
                : null;
        String invocationId = stepContext != null
                ? stepContext.invocationId()
                : context.executionId() + "#" + effectiveAddress;

        try {
            Object output = new InterceptorChain(0, stepContext, input, context).proceed(input);
            if (output == null) {
                throw new IllegalStateException("Step [" + id + "] returned null output");
            }
            long duration = System.nanoTime() - startNanos;
            if (context.isTraceEnabled()) {
                context.traceCollector().recordLeaf(id, effectivePath, invocationId, NodeKind.STEP,
                        FlowResult.Kind.SUCCEEDED, duration, null, null, null);
            }
            context.notifyNodeCompleted(id, effectivePath, NodeKind.STEP, FlowResult.Kind.SUCCEEDED, duration, null, null);
            return FlowResult.succeeded(output);
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
                context.traceCollector().recordLeaf(id, effectivePath, invocationId, NodeKind.STEP,
                        FlowResult.Kind.FAILED, duration, null, null, failure);
            }
            context.notifyNodeCompleted(id, effectivePath, NodeKind.STEP, FlowResult.Kind.FAILED, duration, null, failure);
            return FlowResult.failed(failure);
        }
    }

    @Override
    public NodeDescription describe() {
        return new NodeDescription(id, path, address, NodeKind.STEP, null, null, null, false, null, null, null, null);
    }

    @Override
    public <R> R project(Flow.Projection<R> projection) {
        Flow.StepInfo info = new Flow.StepInfo(id, path, address, contextualStep != null);
        return projection.projectStep(info, step, contextualStep, interceptors);
    }

    private final class InterceptorChain implements StepInterceptor.Chain<Object, Object> {
        private final int index;
        private final StepContext stepContext;
        private final Object currentInput;
        private final ExecutionContext executionContext;

        InterceptorChain(int index, StepContext stepContext, Object currentInput, ExecutionContext executionContext) {
            this.index = index;
            this.stepContext = stepContext;
            this.currentInput = currentInput;
            this.executionContext = executionContext;
        }

        @Override
        public StepContext context() {
            if (stepContext != null) {
                return stepContext;
            }
            return executionContext.createStepContext(id, path, address);
        }

        @Override
        public Object input() {
            return currentInput;
        }

        @Override
        public Object proceed(Object in) throws Exception {
            if (in == null) {
                throw new IllegalArgumentException("Step input passed to proceed must not be null");
            }
            if (index < interceptors.size()) {
                StepInterceptor interceptor = interceptors.get(index);
                Object res = interceptor.intercept(new InterceptorChain(index + 1, stepContext, in, executionContext));
                if (res == null) {
                    throw new IllegalStateException("StepInterceptor returned null output for node [" + id + "]");
                }
                return res;
            }
            if (contextualStep != null) {
                return contextualStep.apply(context(), in);
            }
            return step.apply(in);
        }
    }
}
