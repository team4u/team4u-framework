package com.team4u.framework.flow;

import java.time.Duration;
import java.util.Objects;

/**
 * 内置不可变重试配置。maxAttempts 含首次执行，backoff 为重试间隔且不可为负。
 */
public final class Retry {
    private final int maxAttempts;
    private final Duration backoff;

    public Retry(int maxAttempts, Duration backoff) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.backoff = Objects.requireNonNull(backoff, "backoff must not be null");
        if (backoff.isNegative()) throw new IllegalArgumentException("backoff must not be negative");
    }

    public static Retry maxAttempts(int maxAttempts) {
        return new Retry(maxAttempts, Duration.ZERO);
    }

    public Retry withBackoff(Duration backoff) {
        return new Retry(maxAttempts, backoff);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration backoff() {
        return backoff;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Retry retry = (Retry) o;
        return maxAttempts == retry.maxAttempts && backoff.equals(retry.backoff);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxAttempts, backoff);
    }

    @Override
    public String toString() {
        return "Retry[maxAttempts=" + maxAttempts + ", backoff=" + backoff + "]";
    }
}
