package com.team4u.framework.kv.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可手动推进的测试时钟
 *
 * @author jay.wu
 */
public class SettableClock extends Clock {

    private long millis;

    public SettableClock(long initialMillis) {
        this.millis = initialMillis;
    }

    public void advance(long deltaMillis) {
        millis += deltaMillis;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }
}
