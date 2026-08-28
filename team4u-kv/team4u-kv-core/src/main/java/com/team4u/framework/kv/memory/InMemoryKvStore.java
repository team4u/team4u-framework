package com.team4u.framework.kv.memory;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvEvent;
import com.team4u.framework.kv.KvListener;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.ScanCapable;
import com.team4u.framework.kv.ScoredWindowCapable;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.WatchCapable;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存键值存储
 * <p>
 * 基于 {@link ConcurrentHashMap} 实现，零外部依赖，行为与其他存储实现保持一致
 * （过期语义、SETNX 语义、CAS 语义、计数 TTL 语义、计分窗口语义），
 * 适合单元测试与单实例临时数据。
 * 过期采用读取时惰性判定，{@link #size()} 与 {@link #pruneExpired(String, int)}
 * 提供主动清理入口（写多读少的冷键由清理器调用 pruneExpired 止血；
 * pruneExpired 同批清扫已过期的计数器与计分窗口键）。
 * </p>
 * 时间源可注入（{@link Clock}），便于测试中虚拟时间推进。
 * 实现 {@link CasCapable}、{@link ScanCapable}、{@link WatchCapable}、
 * {@link CounterCapable}、{@link ScoredWindowCapable} 全部能力。
 *
 * @author jay.wu
 */
@Slf4j
public class InMemoryKvStore implements KvStore, CasCapable, ScanCapable, WatchCapable,
        CounterCapable, ScoredWindowCapable, AutoCloseable {


    private final ConcurrentHashMap<SpaceKey, KvRecord> map = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<KvListener>> listeners =
            new ConcurrentHashMap<>();
    /**
     * 计数器与记录值域分开维护，互不干扰（带过期截止时间）
     */
    private final ConcurrentHashMap<SpaceKey, Counter> counters = new ConcurrentHashMap<>();
    /**
     * 计分窗口与记录值域分开维护，互不干扰（带过期截止时间）
     */
    private final ConcurrentHashMap<SpaceKey, Window> windows = new ConcurrentHashMap<>();
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

    /**
     * 物理清理指定键空间下已过期的数据：普通记录、计数器与计分窗口键同批清扫
     *
     * @param maxBatch 单次最大删除数量（三类合计），防止长时间占用
     * @return 实际删除数量（记录 + 计数器 + 窗口）
     */
    @Override
    public int pruneExpired(String space, int maxBatch) {
        long now = now();
        int count = 0;
        for (Map.Entry<SpaceKey, KvRecord> entry : map.entrySet()) {
            if (count >= maxBatch) {
                return count;
            }
            SpaceKey key = entry.getKey();
            if (key.getSpace().equals(space)
                    && entry.getValue().isExpired(now)
                    && map.remove(key, entry.getValue())) {
                fire(new KvEvent(KvEvent.Type.REMOVE, key, null));
                count++;
            }
        }
        for (Map.Entry<SpaceKey, Counter> entry : counters.entrySet()) {
            if (count >= maxBatch) {
                return count;
            }
            SpaceKey key = entry.getKey();
            if (key.getSpace().equals(space)
                    && isExpired(entry.getValue().deadlineAt, now)
                    && counters.remove(key, entry.getValue())) {
                count++;
            }
        }
        for (Map.Entry<SpaceKey, Window> entry : windows.entrySet()) {
            if (count >= maxBatch) {
                return count;
            }
            SpaceKey key = entry.getKey();
            if (key.getSpace().equals(space)
                    && isExpired(entry.getValue().deadlineAt, now)
                    && windows.remove(key, entry.getValue())) {
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

    // ------------------------------------------------- 计数能力

    @Override
    public long incrementAndGet(SpaceKey key, long delta, long ttlMillis) {
        Counter counter = counters.computeIfAbsent(key, k -> new Counter());
        synchronized (counter) {
            long now = now();
            // 惰性过期：到期先重置为 0 再累加，等效于键消失后重建
            if (isExpired(counter.deadlineAt, now)) {
                counter.value.set(0);
                counter.deadlineAt = 0;
            }
            long next = counter.value.addAndGet(delta);
            if (ttlMillis > 0 && counter.deadlineAt == 0) {
                // 键创建（或重置）时设置 TTL；后续递增不刷新
                counter.deadlineAt = KvRecord.expireAtOf(ttlMillis, now);
            }
            return next;
        }
    }

    // ------------------------------------------------- 计分窗口能力

    @Override
    public Verdict offer(SpaceKey key, Offer offer) {
        Objects.requireNonNull(offer, "offer");
        List<String> members = offer.getMembers() == null
                ? java.util.Collections.emptyList()
                : offer.getMembers();
        Window window = windows.computeIfAbsent(key, k -> new Window());
        synchronized (window) {
            long now = now();
            // 惰性过期：到期整键消失，窗口从零重来
            if (isExpired(window.deadlineAt, now)) {
                window.size = 0;
                window.deadlineAt = 0;
            }
            // 裁剪：score 严格大于 cutoff 的成员存活（== cutoff 被裁剪），紧凑左移
            int kept = 0;
            for (int i = 0; i < window.size; i++) {
                if (window.scores[i] > offer.getCutoffScore()) {
                    window.scores[kept++] = window.scores[i];
                }
            }
            window.size = kept;

            int adding = members.size();
            if (adding > 0 && kept + adding > offer.getMaxCount()) {
                // 超限：不添加任何成员（拒绝路径不刷新 TTL）
                return Verdict.builder()
                        .accepted(false)
                        .count(kept)
                        .oldestScore(oldestScoreOf(window))
                        .build();
            }
            ensureCapacity(window, kept + adding);
            for (int i = 0; i < adding; i++) {
                window.scores[window.size++] = offer.getMemberScore();
            }
            if (offer.getTtlMillis() > 0) {
                // 每次成功操作（含窥探）刷新整键 TTL
                window.deadlineAt = KvRecord.expireAtOf(offer.getTtlMillis(), now);
            }
            return Verdict.builder()
                    .accepted(true)
                    .count(window.size)
                    .oldestScore(oldestScoreOf(window))
                    .build();
        }
    }

    /**
     * 窗口现存成员中的最小 score（最老成员）；空窗口返回 null
     */
    private static Long oldestScoreOf(Window window) {
        if (window.size == 0) {
            return null;
        }
        long min = window.scores[0];
        for (int i = 1; i < window.size; i++) {
            if (window.scores[i] < min) {
                min = window.scores[i];
            }
        }
        return min;
    }

    private static void ensureCapacity(Window window, int required) {
        if (required <= window.scores.length) {
            return;
        }
        int newLength = Math.max(required, window.scores.length * 2);
        window.scores = Arrays.copyOf(window.scores, newLength);
    }

    private static boolean isExpired(long deadlineAt, long now) {
        return deadlineAt > 0 && now >= deadlineAt;
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
        counters.clear();
        windows.clear();
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

    /**
     * 带过期截止时间的计数器（deadlineAt 为 0 表示永不过期）
     */
    private static final class Counter {

        private final AtomicLong value = new AtomicLong();

        /**
         * 过期截止时间（epoch 毫秒），0 表示永不过期
         */
        private long deadlineAt;
    }

    /**
     * 带过期截止时间的计分窗口：仅保留成员 score（计数与最老成员计算只依赖 score）
     */
    private static final class Window {

        private long[] scores = new long[8];

        /**
         * 现存成员数量（scores 的有效前缀长度）
         */
        private int size;

        /**
         * 过期截止时间（epoch 毫秒），0 表示永不过期
         */
        private long deadlineAt;
    }
}
