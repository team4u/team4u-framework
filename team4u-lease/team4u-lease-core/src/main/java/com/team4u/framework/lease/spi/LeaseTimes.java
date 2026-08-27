package com.team4u.framework.lease.spi;

public final class LeaseTimes {

    private LeaseTimes() {
    }

    public static long plusMillis(long now, long duration) {
        if (now < 0L) {
            throw new IllegalArgumentException("now must not be negative: " + now);
        }
        if (duration < 0L) {
            throw new IllegalArgumentException("duration must not be negative: " + duration);
        }
        try {
            return Math.addExact(now, duration);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("now + duration overflows Long.MAX_VALUE: "
                    + now + " + " + duration, ex);
        }
    }
}
