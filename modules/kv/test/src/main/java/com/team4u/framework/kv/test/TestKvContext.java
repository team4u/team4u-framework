package com.team4u.framework.kv.test;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.memory.InMemoryKvStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 零外部依赖的 KV 测试上下文：内存存储 + 可推进的虚拟时钟
 * <p>
 * 业务单测中依赖 {@link KvStore} 时，用本上下文替代真实存储，
 * 与生产实现跑同一套契约测试保证行为一致，无需 Mock。
 * </p>
 * <pre>{@code
 * TestKvContext kv = TestKvContext.create();
 * kv.store().put(SpaceKey.of("user", "u1"), KvRecord.of("v1"), PutMode.SET);
 * kv.advanceSeconds(60);   // 虚拟时间推进，验证 TTL 语义
 * }</pre>
 *
 * @author jay.wu
 */
public class TestKvContext implements AutoCloseable {

    private final SettableClock clock = new SettableClock(0L);
    private final InMemoryKvStore store = new InMemoryKvStore(clock);

    public static TestKvContext create() {
        return new TestKvContext();
    }

    /**
     * 被测存储（虚拟时钟驱动）
     */
    public InMemoryKvStore store() {
        return store;
    }

    /**
     * 虚拟时钟：可用于构造依赖 Clock 的组件（锁管理器、生命周期等）
     */
    public Clock clock() {
        return clock;
    }

    /**
     * 推进虚拟时间（毫秒）
     */
    public void advanceMillis(long millis) {
        clock.advance(millis);
    }

    public void advanceSeconds(long seconds) {
        clock.advance(seconds * 1000);
    }

    @Override
    public void close() {
        store.close();
    }

    /**
     * 可手动推进的测试时钟
     *
     * @author jay.wu
     */
    public static class SettableClock extends Clock {

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
}
