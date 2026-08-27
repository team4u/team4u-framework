package com.team4u.framework.kv.memory;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvEvent;
import com.team4u.framework.kv.KvListener;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.ScanCapable;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.WatchCapable;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存键值存储
 * <p>
 * 基于 {@link ConcurrentHashMap} 实现，零外部依赖，行为与其他存储实现保持一致
 * （过期语义、SETNX 语义、CAS 语义），适合单元测试与单实例临时数据。
 * 过期采用读取时惰性判定，{@link #size()} 与 {@link #pruneExpired(String, int)}
 * 提供主动清理入口（写多读少的冷键由清理器调用 pruneExpired 止血）。
 * </p>
 * 时间源可注入（{@link Clock}），便于测试中虚拟时间推进。
 * 实现 {@link CasCapable}、{@link ScanCapable}、{@link WatchCapable} 全部能力。
 *
 * @author jay.wu
 */
@Slf4j
public class InMemoryKvStore implements KvStore, CasCapable, ScanCapable, WatchCapable, AutoCloseable {


    private final ConcurrentHashMap<SpaceKey, KvRecord> map = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<KvListener>> listeners =
            new ConcurrentHashMap<>();
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
            if (map.remove(key, record)) {
                fire(new KvEvent(KvEvent.Type.REMOVE, key, null));
            }
            return null;
        }
        return record;
    }

    @Override
    public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
        Objects.requireNonNull(record, "record");
        if (mode == PutMode.SET) {
            map.put(key, record);
            fire(new KvEvent(KvEvent.Type.PUT, key, record.getValue()));
            return true;
        }

        // IF_ABSENT：基于 compute 保证原子语义，同键已过期数据不阻塞写入
        boolean[] success = new boolean[1];
        map.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isExpired(now())) {
                success[0] = false;
                return existing;
            }
            success[0] = true;
            return record;
        });
        if (success[0]) {
            fire(new KvEvent(KvEvent.Type.PUT, key, record.getValue()));
        }
        return success[0];
    }

    @Override
    public boolean remove(SpaceKey key) {
        KvRecord record = map.remove(key);
        boolean removed = record != null && !record.isExpired(now());
        if (record != null) {
            fire(new KvEvent(KvEvent.Type.REMOVE, key, null));
        }
        return removed;
    }

    @Override
    public boolean expire(SpaceKey key, long ttlMillis) {
        boolean[] renewed = new boolean[1];
        map.compute(key, (ignored, existing) -> {
            if (existing == null || existing.isExpired(now())) {
                renewed[0] = false;
                return existing;
            }
            renewed[0] = true;
            return existing.expire(ttlMillis, now());
        });
        return renewed[0];
    }

    @Override
    public boolean compareAndSet(SpaceKey key, String expectedValue, KvRecord update) {
        Objects.requireNonNull(update, "update");
        boolean[] success = new boolean[1];
        map.compute(key, (ignored, existing) -> {
            if (existing == null || existing.isExpired(now())) {
                success[0] = false;
                return existing == null ? null : existing;
            }
            if (!existing.getValue().equals(expectedValue)) {
                success[0] = false;
                return existing;
            }
            success[0] = true;
            return update;
        });
        if (success[0]) {
            fire(new KvEvent(KvEvent.Type.PUT, key, update.getValue()));
        }
        return success[0];
    }

    @Override
    public boolean compareAndRemove(SpaceKey key, String expectedValue) {
        boolean[] success = new boolean[1];
        map.compute(key, (ignored, existing) -> {
            if (existing == null || existing.isExpired(now())) {
                success[0] = false;
                return existing == null ? null : existing;
            }
            if (!existing.getValue().equals(expectedValue)) {
                success[0] = false;
                return existing;
            }
            success[0] = true;
            return null;
        });
        if (success[0]) {
            fire(new KvEvent(KvEvent.Type.REMOVE, key, null));
        }
        return success[0];
    }

    @Override
    public List<SpaceKey> scan(String space) {
        long now = now();
        List<SpaceKey> keys = new ArrayList<>();
        for (Map.Entry<SpaceKey, KvRecord> entry : map.entrySet()) {
            if (entry.getKey().getSpace().equals(space) && !entry.getValue().isExpired(now)) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    @Override
    public int pruneExpired(String space, int maxBatch) {
        long now = now();
        int count = 0;
        for (Map.Entry<SpaceKey, KvRecord> entry : map.entrySet()) {
            if (count >= maxBatch) {
                break;
            }
            SpaceKey key = entry.getKey();
            if (key.getSpace().equals(space)
                    && entry.getValue().isExpired(now)
                    && map.remove(key, entry.getValue())) {
                fire(new KvEvent(KvEvent.Type.REMOVE, key, null));
                count++;
            }
        }
        return count;
    }

    /**
     * 当前缓存的条目数量，统计前先清理已过期条目（与 base 的 TimedCache.size() 行为对齐）
     */
    public int size() {
        pruneAllExpired();
        return map.size();
    }

    private void pruneAllExpired() {
        long now = now();
        for (Map.Entry<SpaceKey, KvRecord> entry : map.entrySet()) {
            if (entry.getValue().isExpired(now) && map.remove(entry.getKey(), entry.getValue())) {
                fire(new KvEvent(KvEvent.Type.REMOVE, entry.getKey(), null));
            }
        }
    }

    @Override
    public AutoCloseable watch(String space, KvListener listener) {
        CopyOnWriteArrayList<KvListener> list =
                listeners.computeIfAbsent(space, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    @Override
    public void close() {
        map.clear();
        listeners.clear();
    }

    private void fire(KvEvent event) {
        List<KvListener> list = listeners.get(event.getKey().getSpace());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (KvListener listener : list) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("KvListener failed|event={}", event, e);
            }
        }
    }

    private long now() {
        return clock.millis();
    }
}
