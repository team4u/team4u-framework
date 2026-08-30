package com.team4u.framework.flow;

/**
 * 流程运行选项。
 *
 * @author jay.wu
 */
public final class RunOptions {

    private static final RunOptions DEFAULTS = new RunOptions(null, false, null);

    private final String executionId;
    private final boolean trace;
    private final FlowObserver observer;

    private RunOptions(String executionId, boolean trace, FlowObserver observer) {
        this.executionId = executionId;
        this.trace = trace;
        this.observer = observer;
    }

    public static RunOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String executionId() {
        return executionId;
    }

    public boolean isTrace() {
        return trace;
    }

    public FlowObserver observer() {
        return observer;
    }

    public static final class Builder {
        private String executionId;
        private boolean trace;
        private FlowObserver observer;

        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder trace(boolean trace) {
            this.trace = trace;
            return this;
        }

        public Builder observer(FlowObserver observer) {
            this.observer = observer;
            return this;
        }

        public RunOptions build() {
            return new RunOptions(executionId, trace, observer);
        }
    }
}
