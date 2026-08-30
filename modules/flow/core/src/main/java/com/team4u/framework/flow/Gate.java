package com.team4u.framework.flow;

import java.util.Objects;

/**
 * 普通 Policy 在 {@link Policy#before} 中返回的严格闭式决策：Proceed、Reject 或 Fail。
 * 采用 Java 8 闭集设计，不可在模块外部实现或继承。
 */
public abstract class Gate {

    Gate() { }

    public static Gate proceed() {
        return Proceed.INSTANCE;
    }

    public static Gate reject(Reason reason) {
        return new Reject(reason);
    }

    public static Gate fail(Failure failure) {
        return new Fail(failure);
    }

    /** 放行决策，无附加状态。 */
    public static final class Proceed extends Gate {
        private static final Proceed INSTANCE = new Proceed();

        private Proceed() { }

        @Override
        public String toString() {
            return "Proceed";
        }
    }

    public static final class Reject extends Gate {
        private final Reason reason;

        Reject(Reason reason) {
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
        }

        public Reason reason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Reject reject = (Reject) o;
            return reason.equals(reject.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reason);
        }

        @Override
        public String toString() {
            return "Reject[" + reason + "]";
        }
    }

    public static final class Fail extends Gate {
        private final Failure failure;

        Fail(Failure failure) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
        }

        public Failure failure() {
            return failure;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Fail fail = (Fail) o;
            return failure.equals(fail.failure);
        }

        @Override
        public int hashCode() {
            return Objects.hash(failure);
        }

        @Override
        public String toString() {
            return "Fail[" + failure + "]";
        }
    }
}
