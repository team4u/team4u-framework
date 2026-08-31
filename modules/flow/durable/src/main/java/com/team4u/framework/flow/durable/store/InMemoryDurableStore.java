package com.team4u.framework.flow.durable.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;

/**
 * 基于内存并发哈希表（{@link ConcurrentHashMap}）的 DurableStore 内置实现。
 *
 * <p>线程安全且完全基于 JDK 原生组件，适用于单元测试、本地调试与无需跨进程恢复的快速验证场景。
 * 支持 {@link #scanDue} 到期扫描（全量遍历内存表并按 firstWakeAt 升序截取）。</p>
 *
 * @author jay.wu
 */
public final class InMemoryDurableStore implements DurableStore {
    private final ConcurrentHashMap<String, DurableSnapshot> snapshots =
            new ConcurrentHashMap<String, DurableSnapshot>();

    @Override
    public Optional<DurableSnapshot> load(String executionId) {
        return Optional.ofNullable(snapshots.get(text(executionId)));
    }

    @Override
    public boolean compareAndSet(String executionId, long expectedRevision,
                                 DurableSnapshot update) {
        final String key = text(executionId);
        Objects.requireNonNull(update, "update must not be null");
        if (!key.equals(update.executionId())) {
            throw new IllegalArgumentException("snapshot executionId does not match store key");
        }
        if (expectedRevision < -1) {
            throw new IllegalArgumentException("expectedRevision must be at least -1");
        }
        if (update.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("update revision must equal expectedRevision + 1");
        }
        if (expectedRevision == -1) {
            return snapshots.putIfAbsent(key, update) == null;
        }
        final AtomicBoolean changed = new AtomicBoolean();
        snapshots.computeIfPresent(key, (ignored, current) -> {
            if (current.revision() != expectedRevision) return current;
            changed.set(true);
            return update;
        });
        return changed.get();
    }

    /**
     * 扫描已到达定时唤醒时刻的 ACTIVE 快照：全量遍历内存表，按 firstWakeAt 升序返回至多 limit 条。
     *
     * @param now  当前时刻
     * @param limit 单次返回的最大条数（正数）
     * @return 到期快照列表
     */
    @Override
    public Optional<List<DurableSnapshot>> scanDue(Instant now, int limit) {
        Objects.requireNonNull(now, "now must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<DurableSnapshot> due = new ArrayList<DurableSnapshot>();
        for (DurableSnapshot snapshot : snapshots.values()) {
            if (snapshot.lifecycle() == DurableLifecycle.ACTIVE
                    && snapshot.firstWakeAt() != null
                    && !now.isBefore(snapshot.firstWakeAt())) {
                due.add(snapshot);
            }
        }
        due.sort(Comparator.comparing(DurableSnapshot::firstWakeAt));
        return Optional.of(due.size() > limit
                ? new ArrayList<DurableSnapshot>(due.subList(0, limit)) : due);
    }

    /** 测试辅助：绕过 CAS 直接落库一张手工构造的快照（仅同包测试使用）。 */
    void insertForTest(String executionId, DurableSnapshot snapshot) {
        snapshots.put(text(executionId), snapshot);
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "executionId must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(
                "executionId must not be blank");
        return value;
    }
}
