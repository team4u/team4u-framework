package com.team4u.framework.flow;

import java.util.Objects;
import java.util.function.Function;

/**
 * 业务逻辑的严格四态结果：Accepted（携带输出）、Rejected、Skipped、Failed。
 * 仅 Accepted 携带输出值；其余三态保留 scope entry 以便降级、恢复或重试。
 * 采用 Java 8 闭集设计，不可在模块外部实现或继承。
 */
public abstract class Outcome<T> {

    Outcome() { }

    public static <T> Outcome<T> accepted(T value) {
        return new Accepted<T>(value);
    }

    public static <T> Outcome<T> rejected(Reason reason) {
        return new Rejected<T>(reason);
    }

    public static <T> Outcome<T> skipped(Reason reason) {
        return new Skipped<T>(reason);
    }

    public static <T> Outcome<T> failed(Failure failure) {
        return new Failed<T>(failure);
    }

    /** 仅对 Accepted 的输出应用映射，其余状态原样透传类型参数。 */
    public <R> Outcome<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        if (this instanceof Accepted) {
            return accepted(mapper.apply(((Accepted<T>) this).value()));
        } else if (this instanceof Rejected) {
            return rejected(((Rejected<T>) this).reason());
        } else if (this instanceof Skipped) {
            return skipped(((Skipped<T>) this).reason());
        } else if (this instanceof Failed) {
            return failed(((Failed<T>) this).failure());
        }
        throw new IllegalStateException("Unknown Outcome: " + getClass());
    }

    public Kind kind() {
        if (this instanceof Accepted) {
            return Kind.ACCEPTED;
        } else if (this instanceof Rejected) {
            return Kind.REJECTED;
        } else if (this instanceof Skipped) {
            return Kind.SKIPPED;
        } else if (this instanceof Failed) {
            return Kind.FAILED;
        }
        throw new IllegalStateException("Unknown Outcome: " + getClass());
    }

    public enum Kind { ACCEPTED, REJECTED, SKIPPED, FAILED }

    /** 成功态，携带非 null 输出值。 */
    public static final class Accepted<T> extends Outcome<T> {
        private final T value;

        Accepted(T value) {
            this.value = Objects.requireNonNull(value, "accepted value must not be null");
        }

        public T value() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Accepted<?> accepted = (Accepted<?>) o;
            return value.equals(accepted.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }

        @Override
        public String toString() {
            return "Accepted[" + value + "]";
        }
    }

    /** 业务拒绝，携带稳定 reason，不进入失败恢复而是触发降级。 */
    public static final class Rejected<T> extends Outcome<T> {
        private final Reason reason;

        Rejected(Reason reason) {
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
        }

        public Reason reason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Rejected<?> rejected = (Rejected<?>) o;
            return reason.equals(rejected.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reason);
        }

        @Override
        public String toString() {
            return "Rejected[" + reason + "]";
        }
    }

    /** 弃权/跳过，携带 reason；用于可重试或可降级的无适用分支场景。 */
    public static final class Skipped<T> extends Outcome<T> {
        private final Reason reason;

        Skipped(Reason reason) {
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
        }

        public Reason reason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Skipped<?> skipped = (Skipped<?>) o;
            return reason.equals(skipped.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reason);
        }

        @Override
        public String toString() {
            return "Skipped[" + reason + "]";
        }
    }

    /** 执行失败，携带 Failure，触发重试或失败恢复。 */
    public static final class Failed<T> extends Outcome<T> {
        private final Failure failure;

        Failed(Failure failure) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
        }

        public Failure failure() {
            return failure;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Failed<?> failed = (Failed<?>) o;
            return failure.equals(failed.failure);
        }

        @Override
        public int hashCode() {
            return Objects.hash(failure);
        }

        @Override
        public String toString() {
            return "Failed[" + failure + "]";
        }
    }
}
