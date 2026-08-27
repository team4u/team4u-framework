package com.team4u.framework.kv.memory;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存键值存储
 * <p>
 * 基于 {@link ConcurrentHashMap} 实现，零外部依赖，行为与其他存储实现保持一致，
 * 适合单元测试与单实例临时数据。过期采用读取时惰性判定，无后台清理线程。
 * </p>
 * 时间源可注入（{@link Clock}），便于测试中虚拟时间推进。
 */
public class InMemoryKvStore implements KvStore, AutoCloseable {

    private final ConcurrentHashMap<SpaceKey, KvRecord> map = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryKvStore() {
        this(Clock.systemUTC());
    }

    public InMemoryKvStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public KvRecord get(SpaceKey key) {
        KvRecord record = map.get(key);
        if (record == null) {
            return null;
        }
        if (record.isExpired(now())) {
            map.remove(key, record);
            return null;
        }
        return record;
    }

    @Override
    public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
        if (mode == PutMode.SET) {
            map.put(key, record);
            return true;
        }

        // IF_ABSENT：基于 compute 保证原子语义，同键已过期数据不阻塞写入
        AtomicReference<Boolean> success = new AtomicReference<>();
        map.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isExpired(now())) {
                success.set(false);
                return existing;
            }
            success.set(true);
            return record;
        });
        return success.get();
    }

    @Override
    public boolean remove(SpaceKey key) {
        KvRecord record = map.remove(key);
        return record != null && !record.isExpired(now());
    }

    @Override
    public boolean expire(SpaceKey key, long ttlMillis) {
        KvRecord record = map.get(key);
        if (record == null || record.isExpired(now())) {
            return false;
        }
        return map.replace(key, record, record.expire(ttlMillis, now()));
    }

    /**
     * 当前缓存的条目数量（含尚未被惰性清理的已过期条目）
     */
    public int size() {
        return map.size();
    }

    @Override
    public void close() {
        map.clear();
    }

    private long now() {
        return clock.millis();
    }
}
