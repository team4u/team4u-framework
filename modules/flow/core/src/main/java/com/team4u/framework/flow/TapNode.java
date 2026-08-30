package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 副作用动作节点实现（原样透传输入值）。
 *
 * @author jay.wu
 */
final class TapNode implements FlowNode {

    private final String id;
    private final String path;
    private final String address;
    private final Action<Object> action;
    private final Action.Contextual<Object> contextualAction;
    private final List<StepInterceptor> interceptors;

    @SuppressWarnings("unchecked")
    TapNode(String id, String path, String address,
            Action<?> action,
            Action.Contextual<?> contextualAction,
            List<StepInterceptor> interceptors) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = path != null ? path : id;
        this.address = address != null ? address : id;
        this.action = (Action<Object>) action;
        this.contextualAction = (Action.Contextual<Object>) contextualAction;
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
        return NodeKind.TAP;
    }

    @Override
    public FlowResult<Object> execute(ExecutionContext context, Object input) {
        long startNanos = System.nanoTime();
        context.notifyNodeStarted(id, path, NodeKind.TAP);

        StepContext stepContext = (contextualAction != null || !interceptors.isEmpty())
                ? context.createStepContext(id, path, address)
                : null;
        String invocationId = stepContext != null ? stepContext.invocationId() : null;

        try {
            Object output = new InterceptorChain(0, stepContext, input, context).proceed(input);
            if (output == null) {
                throw new IllegalStateException("StepInterceptor returned null output for tap node [" + id + "]");
            }
            long duration = System.nanoTime() - startNanos;
            if (context.isTraceEnabled()) {
                context.traceCollector().recordLeaf(id, path, invocationId, NodeKind.TAP,
                        FlowResult.Kind.SUCCEEDED, duration, null, null, null);
            }
            context.notifyNodeCompleted(id, path, NodeKind.TAP, FlowResult.Kind.SUCCEEDED, duration, null, null);
            return FlowResult.succeeded(output);
        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            }
            long duration = System.nanoTime() - startNanos;
            FailureContext failure = new FailureContext(id, path, t);
            if (context.isTraceEnabled()) {
                context.traceCollector().recordLeaf(id, path, invocationId, NodeKind.TAP,
                        FlowResult.Kind.FAILED, duration, null, null, failure);
            }
            context.notifyNodeCompleted(id, path, NodeKind.TAP, FlowResult.Kind.FAILED, duration, null, failure);
            return FlowResult.failed(failure);
        }
    }

    @Override
    public NodeDescription describe() {
        return new NodeDescription(id, path, address, NodeKind.TAP, null, null, null, false, null, null, null, null);
    }

    @Override
    public <R> R project(Flow.Projection<R> projection) {
        Flow.TapInfo info = new Flow.TapInfo(id, path, address, contextualAction != null);
        return projection.projectTap(info, action, contextualAction, interceptors);
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
                throw new IllegalArgumentException("Tap input passed to proceed must not be null");
            }
            if (index < interceptors.size()) {
                StepInterceptor interceptor = interceptors.get(index);
                Object res = interceptor.intercept(new InterceptorChain(index + 1, stepContext, in, executionContext));
                if (res == null) {
                    throw new IllegalStateException("StepInterceptor returned null output for tap node [" + id + "]");
                }
                return res;
            }
            if (contextualAction != null) {
                contextualAction.execute(context(), in);
            } else {
                action.execute(in);
            }
            return in;
        }
    }
}
