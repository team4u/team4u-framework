package com.team4u.framework.flow;

import java.util.Objects;

/**
 * Local 执行的严格三态结果：Completed、Suspended 或 Cancelled 三选一。
 * 采用 Java 8 闭集设计，不可在模块外部实现或继承。
 */
public abstract class FlowResult<O> {

    FlowResult() { }

    public static <O> FlowResult<O> completed(Outcome<O> outcome) {
        return new Completed<O>(outcome);
    }

    public static <O> FlowResult<O> suspended(Suspension<O> suspension) {
        return new Suspended<O>(suspension);
    }

    public static <O> FlowResult<O> cancelled(String executionId) {
        return new Cancelled<O>(executionId);
    }

    /** 要求结果为 Completed/Accepted，否则抛异常。 */
    public O requireAccepted() {
        if (this instanceof Completed) {
            Outcome<O> outcome = ((Completed<O>) this).outcome();
            if (outcome instanceof Outcome.Accepted) {
                return ((Outcome.Accepted<O>) outcome).value();
            }
        }
        throw new IllegalStateException("Flow did not complete with Accepted");
    }

    /** 正常结束，携带最终 Outcome。 */
    public static final class Completed<O> extends FlowResult<O> {
        private final Outcome<O> outcome;

        Completed(Outcome<O> outcome) {
            this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        }

        public Outcome<O> outcome() {
            return outcome;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Completed<?> completed = (Completed<?>) o;
            return outcome.equals(completed.outcome);
        }

        @Override
        public int hashCode() {
            return Objects.hash(outcome);
        }

        @Override
        public String toString() {
            return "Completed[" + outcome + "]";
        }
    }

    /** 执行挂起，携带单次可用的 Suspension 续接句柄。 */
    public static final class Suspended<O> extends FlowResult<O> {
        private final Suspension<O> suspension;

        Suspended(Suspension<O> suspension) {
            this.suspension = Objects.requireNonNull(suspension, "suspension must not be null");
        }

        public Suspension<O> suspension() {
            return suspension;
        }

        /** 当前挂起是否在等待指定 ResumePoint（按 name 匹配）。 */
        public boolean awaiting(ResumePoint<?> point) {
            return Objects.requireNonNull(point, "point must not be null").name()
                    .equals(suspension.resumePoint());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Suspended<?> suspended = (Suspended<?>) o;
            return suspension.equals(suspended.suspension);
        }

        @Override
        public int hashCode() {
            return Objects.hash(suspension);
        }

        @Override
        public String toString() {
            return "Suspended[" + suspension + "]";
        }
    }

    /** 执行被取消，仅保留 executionId。 */
    public static final class Cancelled<O> extends FlowResult<O> {
        private final String executionId;

        Cancelled(String executionId) {
            this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        }

        public String executionId() {
            return executionId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Cancelled<?> cancelled = (Cancelled<?>) o;
            return executionId.equals(cancelled.executionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(executionId);
        }

        @Override
        public String toString() {
            return "Cancelled[" + executionId + "]";
        }
    }
}
