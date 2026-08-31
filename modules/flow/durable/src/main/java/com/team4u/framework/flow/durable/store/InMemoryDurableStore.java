package com.team4u.framework.flow.durable.store;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;

/**
 * 基于内存并发哈希表（{@link ConcurrentHashMap}）的 DurableStore 内置实现。
 *
 * <p>线程安全且完全基于 JDK 原生组件，适用于单元测试、本地调试与无需跨进程恢复的快速验证场景。</p>
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

    /** 测试辅助：绕过 CAS 直接落库一张手工构造的快照。 */
    public void insertForTest(String executionId, DurableSnapshot snapshot) {
        snapshots.put(text(executionId), snapshot);
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "executionId must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(
                "executionId must not be blank");
        return value;
    }
}

