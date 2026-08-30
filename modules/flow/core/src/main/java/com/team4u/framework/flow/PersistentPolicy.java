package com.team4u.framework.flow;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable 控制策略扩展点，其不可变状态由框架持久化。
 * before 决定放行/等待/拒绝/失败，after 决定立即返回或定时重试。
 */
public interface PersistentPolicy<K, S> {
    S initialState(K key);

    Before<S> before(PolicyContext context, K key, S state);

    After<S> after(PolicyContext context, K key, S state, Completion completion);

    /** before 的四种闭式决策。采用 Java 8 闭集设计，不可在模块外部实现或继承。 */
    abstract class Before<S> {
        Before() { }
    }

    /** 放行执行，携带更新后的状态。 */
    final class Proceed<S> extends Before<S> {
        private final S state;

        Proceed(S state) {
            this.state = Objects.requireNonNull(state, "state must not be null");
        }

        public S state() {
            return state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Proceed<?> proceed = (Proceed<?>) o;
            return state.equals(proceed.state);
        }

        @Override
        public int hashCode() {
            return Objects.hash(state);
        }

        @Override
        public String toString() {
            return "Proceed[state=" + state + "]";
        }
    }

    /** 等待至指定时刻后再继续评估，携带状态。 */
    final class WaitUntil<S> extends Before<S> {
        private final Instant instant;
        private final S state;

        WaitUntil(Instant instant, S state) {
            this.instant = Objects.requireNonNull(instant, "instant must not be null");
            this.state = Objects.requireNonNull(state, "state must not be null");
        }

        public Instant instant() {
            return instant;
        }

        public S state() {
            return state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            WaitUntil<?> waitUntil = (WaitUntil<?>) o;
            return instant.equals(waitUntil.instant) && state.equals(waitUntil.state);
        }

        @Override
        public int hashCode() {
            return Objects.hash(instant, state);
        }

        @Override
        public String toString() {
            return "WaitUntil[instant=" + instant + ", state=" + state + "]";
        }
    }

    /** 拒绝执行，携带 reason 与状态。 */
    final class Reject<S> extends Before<S> {
        private final Reason reason;
        private final S state;

        Reject(Reason reason, S state) {
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
            this.state = Objects.requireNonNull(state, "state must not be null");
        }

        public Reason reason() {
            return reason;
        }

        public S state() {
            return state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Reject<?> reject = (Reject<?>) o;
            return reason.equals(reject.reason) && state.equals(reject.state);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reason, state);
        }

        @Override
        public String toString() {
            return "Reject[reason=" + reason + ", state=" + state + "]";
        }
    }

    /** 判定失败，携带 failure 与状态。 */
    final class Fail<S> extends Before<S> {
        private final Failure failure;
        private final S state;

        Fail(Failure failure, S state) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
            this.state = Objects.requireNonNull(state, "state must not be null");
        }

        public Failure failure() {
            return failure;
        }

        public S state() {
            return state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Fail<?> fail = (Fail<?>) o;
            return failure.equals(fail.failure) && state.equals(fail.state);
        }

        @Override
        public int hashCode() {
            return Objects.hash(failure, state);
        }

        @Override
        public String toString() {
            return "Fail[failure=" + failure + ", state=" + state + "]";
        }
    }

    /** after 的两种闭式决策。采用 Java 8 闭集设计，不可在模块外部实现或继承。 */
    abstract class After<S> {
        After() { }
    }

    /** 立即返回，携带最终状态。 */
    final class Return<S> extends After<S> {
        private final S state;

        Return(S state) {
            this.state = Objects.requireNonNull(state, "state must not be null");
        }

        public S state() {
            return state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Return<?> aReturn = (Return<?>) o;
            return state.equals(aReturn.state);
        }

        @Override
        public int hashCode() {
            return Objects.hash(state);
        }

        @Override
        public String toString() {
            return "Return[state=" + state + "]";
        }
    }

    /** 在指定时刻重试，携带状态。 */
    final class RetryAt<S> extends After<S> {
        private final Instant instant;
        private final S state;

        RetryAt(Instant instant, S state) {
            this.instant = Objects.requireNonNull(instant, "instant must not be null");
            this.state = Objects.requireNonNull(state, "state must not be null");
        }

        public Instant instant() {
            return instant;
        }

        public S state() {
            return state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RetryAt<?> retryAt = (RetryAt<?>) o;
            return instant.equals(retryAt.instant) && state.equals(retryAt.state);
        }

        @Override
        public int hashCode() {
            return Objects.hash(instant, state);
        }

        @Override
        public String toString() {
            return "RetryAt[instant=" + instant + ", state=" + state + "]";
        }
    }

    static <S> Before<S> proceed(S state) {
        return new Proceed<S>(state);
    }

    static <S> Before<S> waitUntil(Instant instant, S state) {
        return new WaitUntil<S>(instant, state);
    }

    static <S> Before<S> reject(Reason reason, S state) {
        return new Reject<S>(reason, state);
    }

    static <S> Before<S> fail(Failure failure, S state) {
        return new Fail<S>(failure, state);
    }

    static <S> After<S> returning(S state) {
        return new Return<S>(state);
    }

    static <S> After<S> retryAt(Instant instant, S state) {
        return new RetryAt<S>(instant, state);
    }
}
