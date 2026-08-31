package com.team4u.framework.flow.durable.kv;

import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.serializer.json.JsonUtil;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 {@link KvStore} 统一键值存储抽象的 {@link DurableStore} 实现。
 *
 * <p>核心能力与设计：
 * <ul>
 *   <li><b>多存储后端适配</b>：无缝支持内存（{@code InMemoryKvStore}）、Redis（{@code RedisKvStore}）、JDBC（{@code JdbcKvStore}）等任意后端；</li>
 *   <li><b>严格 CAS 乐观锁</b>：基于 {@link CasCapable} 实现跨节点并发版本控制，防覆写与防并发竞争；</li>
 *   <li><b>支持装饰链</b>：天然支持 KvStore 的重试（{@code RetryableStore}）、指标观测（{@code ObservedStore}）、热替换（{@code HotSwapStore}）等装饰器；</li>
 *   <li><b>按生命周期分流的 TTL 策略</b>：终态（COMPLETED/CANCELLED）快照按 {@code terminalTtlMillis} 归档淘汰，
 *       非终态（ACTIVE/SUSPENDED）快照按 {@code activeTtlMillis} 管理（默认 0 表示永不过期，
 *       保证挂起等待审批等长周期流程不会被静默过期删除）。</li>
 * </ul>
 * </p>
 *
 * <p><b>space 命名约束</b>：底层 {@link SpaceKey} 以 {@code space:key} 拼接物理键，
 * 因此 space 不得包含分隔符 {@code ':'} 或空白字符，否则构造期抛出 {@link IllegalArgumentException}。</p>
 *
 * @author jay.wu
 */
public class KvDurableStore implements DurableStore {

    public static final String DEFAULT_SPACE = "flow_durable";

    private final KvStore store;
    private final CasCapable casStore;
    private final String space;
    private final long terminalTtlMillis;
    private final long activeTtlMillis;
    private final Clock clock;

    /**
     * 以默认空间名（{@value #DEFAULT_SPACE}）与永不过期策略创建存储实例。
     *
     * @param store 底层 KvStore 实现，必须支持 {@link CasCapable} 能力
     */
    public KvDurableStore(KvStore store) {
        this(store, DEFAULT_SPACE, 0L, 0L, Clock.systemUTC());
    }

    /**
     * 以指定空间名与永不过期策略创建存储实例。
     *
     * @param store 底层 KvStore 实现，必须支持 {@link CasCapable} 能力
     * @param space 键空间名称，不能为空且不得包含 ':' 或空白字符
     */
    public KvDurableStore(KvStore store, String space) {
        this(store, space, 0L, 0L, Clock.systemUTC());
    }

    /**
     * 以指定空间名与终态 TTL 创建存储实例（兼容旧签名，语义修复版）。
     *
     * <p><b>行为变更说明</b>：旧版将同一 TTL 应用于全部快照，导致挂起等审批的
     * 非终态快照被静默过期删除。本重载现在将 TTL 仅应用于终态
     * （COMPLETED/CANCELLED）快照的归档淘汰，非终态（ACTIVE/SUSPENDED）快照
     * 默认永不过期。若确需为非终态设置 TTL，请使用
     * {@link #KvDurableStore(KvStore, String, long, long, Clock)}。</p>
     *
     * @param store    底层 KvStore 实现，必须支持 {@link CasCapable} 能力
     * @param space    键空间名称，不能为空且不得包含 ':' 或空白字符
     * @param ttlMillis 终态快照存活时长（毫秒），小于等于 0 视为永不过期；非终态快照永不过期
     */
    public KvDurableStore(KvStore store, String space, long ttlMillis) {
        this(store, space, ttlMillis, 0L, Clock.systemUTC());
    }

    /**
     * 以区分生命周期的 TTL 策略创建存储实例。
     *
     * @param store             底层 KvStore 实现，必须支持 {@link CasCapable} 能力
     * @param space             键空间名称，不能为空且不得包含 ':' 或空白字符
     * @param terminalTtlMillis 终态（COMPLETED/CANCELLED）快照存活时长（毫秒），小于等于 0 视为永不过期
     * @param activeTtlMillis   非终态（ACTIVE/SUSPENDED）快照存活时长（毫秒），小于等于 0 视为永不过期（推荐默认）
     */
    public KvDurableStore(KvStore store, String space, long terminalTtlMillis,
                          long activeTtlMillis) {
        this(store, space, terminalTtlMillis, activeTtlMillis, Clock.systemUTC());
    }

    /**
     * 完整参数构造方法。
     *
     * @param store             底层 KvStore 实现，必须支持 {@link CasCapable} 能力
     * @param space             键空间名称，不能为空且不得包含 ':' 或空白字符
     * @param terminalTtlMillis 终态（COMPLETED/CANCELLED）快照存活时长（毫秒），小于等于 0 视为永不过期
     * @param activeTtlMillis   非终态（ACTIVE/SUSPENDED）快照存活时长（毫秒），小于等于 0 视为永不过期（推荐默认）
     * @param clock             时钟源，用于计算过期时间戳
     */
    public KvDurableStore(KvStore store, String space, long terminalTtlMillis,
                          long activeTtlMillis, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.space = spaceName(space);
        this.terminalTtlMillis = Math.max(0L, terminalTtlMillis);
        this.activeTtlMillis = Math.max(0L, activeTtlMillis);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        this.casStore = KvStores.capabilityOf(store, CasCapable.class);
        if (this.casStore == null) {
            throw new IllegalArgumentException(
                    "KvDurableStore requires a CasCapable store (through decorator chains), got: "
                            + store.getClass().getName());
        }
    }

    @Override
    public Optional<DurableSnapshot> load(String executionId) {
        SpaceKey key = SpaceKey.of(space, text(executionId, "executionId"));
        KvRecord record = store.get(key);
        if (record == null || record.getValue() == null) {
            return Optional.empty();
        }
        DurableSnapshotDto dto = JsonUtil.toBean(record.getValue(), DurableSnapshotDto.class);
        return dto != null ? Optional.of(dto.toSnapshot()) : Optional.empty();
    }

    @Override
    public boolean compareAndSet(String executionId, long expectedRevision, DurableSnapshot update) {
        final String id = text(executionId, "executionId");
        Objects.requireNonNull(update, "update must not be null");
        if (!id.equals(update.executionId())) {
            throw new IllegalArgumentException("snapshot executionId does not match store key");
        }
        if (expectedRevision < -1) {
            throw new IllegalArgumentException("expectedRevision must be at least -1");
        }
        if (update.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("update revision must equal expectedRevision + 1");
        }

        SpaceKey key = SpaceKey.of(space, id);
        String json = JsonUtil.toJsonStr(DurableSnapshotDto.fromSnapshot(update));
        KvRecord newRecord = KvRecord.of(json, ttlMillisFor(update), clock.millis());

        // 首次创建（Create-If-Absent）
        if (expectedRevision == -1) {
            return store.put(key, newRecord, PutMode.IF_ABSENT);
        }

        // 版本 CAS 更新
        KvRecord currentRecord = store.get(key);
        if (currentRecord == null || currentRecord.getValue() == null) {
            return false;
        }

        DurableSnapshotDto currentDto = JsonUtil.toBean(currentRecord.getValue(), DurableSnapshotDto.class);
        if (currentDto == null || currentDto.getRevision() != expectedRevision) {
            return false;
        }

        return casStore.compareAndSet(key, currentRecord.getValue(), newRecord);
    }

    /**
     * 扫描已到达定时唤醒时刻的 ACTIVE 快照。
     *
     * <p>实现基于底层 KvStore 的 {@code ScanCapable} 能力（如 InMemoryKvStore）：
     * 扫描键空间全部键后逐条加载并过滤 {@code firstWakeAt <= now} 的 ACTIVE 快照，按到期时间升序截取。
     * 若底层存储（及其装饰链）不支持扫描能力，返回 {@link Optional#empty()}——
     * 此类后端（如 Redis SCAN 成本敏感场景）建议维护外部的到期时间索引（如 ZSET）或独立延迟队列驱动定时唤醒调度。</p>
     *
     * @param now  当前时刻
     * @param limit 单次返回的最大条数（正数）
     * @return 到期快照列表；存储不支持扫描能力时返回 empty
     */
    @Override
    public Optional<List<DurableSnapshot>> scanDue(java.time.Instant now, int limit) {
        Objects.requireNonNull(now, "now must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        com.team4u.framework.kv.ScanCapable scanStore =
                KvStores.capabilityOf(store, com.team4u.framework.kv.ScanCapable.class);
        if (scanStore == null) {
            return Optional.empty();
        }
        List<DurableSnapshot> due = new ArrayList<DurableSnapshot>();
        for (SpaceKey key : scanStore.scan(space)) {
            Optional<DurableSnapshot> found = load(key.getKey());
            if (!found.isPresent()) {
                continue;
            }
            DurableSnapshot snapshot = found.get();
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

    /**
     * 按快照生命周期选择 TTL：终态用 terminalTtl，非终态用 activeTtl。
     */
    private long ttlMillisFor(DurableSnapshot update) {
        return update.lifecycle() == DurableLifecycle.COMPLETED
                || update.lifecycle() == DurableLifecycle.CANCELLED
                ? terminalTtlMillis : activeTtlMillis;
    }

    /**
     * 获取底层 KvStore 实例。
     */
    public KvStore store() {
        return store;
    }

    /**
     * 获取配置的键空间名称。
     */
    public String space() {
        return space;
    }

    /**
     * 获取终态（COMPLETED/CANCELLED）快照的 TTL 存活毫秒数（0 表示永不过期）。
     */
    public long terminalTtlMillis() {
        return terminalTtlMillis;
    }

    /**
     * 获取非终态（ACTIVE/SUSPENDED）快照的 TTL 存活毫秒数（0 表示永不过期）。
     */
    public long activeTtlMillis() {
        return activeTtlMillis;
    }

    /**
     * 获取使用的时钟源。
     */
    public Clock clock() {
        return clock;
    }

    /** space 命名校验：非空、不含 ':' 分隔符、不含空白字符。 */
    private static String spaceName(String value) {
        Objects.requireNonNull(value, "space must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("space must not be blank");
        }
        if (value.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "space must not contain ':' (physical key separator): " + value);
        }
        if (!value.trim().equals(value)) {
            throw new IllegalArgumentException(
                    "space must not contain leading/trailing whitespace: " + value);
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                throw new IllegalArgumentException(
                        "space must not contain whitespace characters: " + value);
            }
        }
        return value;
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
