package com.team4u.framework.flow.durable.kv;

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
 *   <li><b>可选 TTL 自动过期</b>：支持为流程快照设置生命周期（TTL），实现历史归档数据自动淘汰。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public class KvDurableStore implements DurableStore {

    public static final String DEFAULT_SPACE = "flow_durable";

    private final KvStore store;
    private final CasCapable casStore;
    private final String space;
    private final long ttlMillis;
    private final Clock clock;

    /**
     * 以默认空间名（{@value #DEFAULT_SPACE}）与永不过期策略创建存储实例。
     *
     * @param store 底层 KvStore 实现，必须支持 {@link CasCapable} 能力
     */
    public KvDurableStore(KvStore store) {
        this(store, DEFAULT_SPACE, 0L, Clock.systemUTC());
    }

    /**
     * 以指定空间名与永不过期策略创建存储实例。
     *
     * @param store 底层 KvStore 实现，必须支持 {@link CasCapable} 能力
     * @param space 键空间名称，不能为空且不得包含 ':'
     */
    public KvDurableStore(KvStore store, String space) {
        this(store, space, 0L, Clock.systemUTC());
    }

    /**
     * 以指定空间名与有效期时长创建存储实例。
     *
     * @param store     底层 KvStore 实现，必须支持 {@link CasCapable} 能力
     * @param space     键空间名称，不能为空且不得包含 ':'
     * @param ttlMillis 存活时长（毫秒），小于等于 0 视为永不过期
     */
    public KvDurableStore(KvStore store, String space, long ttlMillis) {
        this(store, space, ttlMillis, Clock.systemUTC());
    }

    /**
     * 完整参数构造方法。
     *
     * @param store     底层 KvStore 实现，必须支持 {@link CasCapable} 能力
     * @param space     键空间名称，不能为空且不得包含 ':'
     * @param ttlMillis 存活时长（毫秒），小于等于 0 视为永不过期
     * @param clock     时钟源，用于计算过期时间戳
     */
    public KvDurableStore(KvStore store, String space, long ttlMillis, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.space = text(space, "space");
        this.ttlMillis = Math.max(0L, ttlMillis);
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
        KvRecord newRecord = (ttlMillis > 0)
                ? KvRecord.of(json, ttlMillis, clock.millis())
                : KvRecord.of(json);

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
     * 获取配置的 TTL 存活毫秒数（0 表示永不过期）。
     */
    public long ttlMillis() {
        return ttlMillis;
    }

    /**
     * 获取使用的时钟源。
     */
    public Clock clock() {
        return clock;
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
