package com.team4u.framework.lease.api;

import java.time.Duration;

final class Durations {

    private Durations() {
    }

    static long requireExactMillis(Duration duration, String name) {
        if (duration == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        try {
            long seconds = duration.getSeconds();
            long nanos = duration.getNano();
            long millis = seconds * 1000L + nanos / 1_000_000L;
            if (nanos % 1_000_000L != 0L || millis / 1000L != seconds) {
                throw new ArithmeticException("lossy conversion");
            }
            return millis;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(name + " must fit in milliseconds", ex);
        }
    }
}
