package com.team4u.framework.kv.tiered;

import com.team4u.framework.base.cache.Cache;
import com.team4u.framework.base.cache.TimedCache;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;

import java.time.Clock;

/**
 * 分层键值存储装饰器：L1 本地缓存 + L2 远程存储
 * <p>
 * 读路径：L1 命中直接返回（零远程开销）；未命中穿透到 L2，命中的记录回填 L1。
 * 写路径：写直通（write-through）—— 先写 L2，成功后同步更新 L1。
 * 删除路径：删除 L2 后在 L1 写入带有效期的<b>墓碑（tombstone）</b>，
 * 防止同键旧值在 L2 副本同步延迟期间从 L1「死灰复燃」。
 * </p>
 * 正确性说明：
 * <ul>
 *     <li>即使 L1 缓存实现（如 {@link TimedCache}）尚未淘汰条目，
 *     读取时也会按记录自身的过期时间戳兜底判定，不会返回已过期数据</li>
 *     <li>墓碑过期后读取自动回退到 L2，因此 L2 的后续写入不受影响</li>
 *     <li>本实现不处理多实例间的 L1 失效广播，跨实例一致性窗口为 L1 的 TTL</li>
 * </ul>
 */
public class TieredStore implements KvStore {

    private final KvStore l2;
    private final Cache<SpaceKey, Entry> l1;
    private final Config config;
    private final Clock clock;

    /**
     * @param l2           远程（权威）存储
     * @param l1TtlMillis  L1 缓存条目有效期（毫秒），小于等于 0 表示仅按记录自身过期时间淘汰
     * @param config       分层配置（墓碑 TTL 等）
     */
    public TieredStore(KvStore l2, long l1TtlMillis, Config config) {
        this(l2, new TimedCache<>(l1TtlMillis), config, Clock.systemUTC());
    }

    public TieredStore(KvStore l2, Cache<SpaceKey, Entry> l1, Config config, Clock clock) {
        this.l2 = l2;
        this.l1 = l1;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public KvRecord get(SpaceKey key) {
        Entry entry = l1.get(key);
        if (entry != null) {
            if (entry.isEffectiveTombstone(now())) {
                return null;
            }
            if (entry.isValueAlive(now())) {
                return entry.getRecord();
            }
            // 值已过期：失效 L1 条目，穿透到 L2
            l1.remove(key);
        }

        KvRecord record = l2.get(key);
        if (record != null) {
            l1.put(key, Entry.ofValue(record));
        }
        return record;
    }

    @Override
    public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
        boolean success = l2.put(key, record, mode);
        if (success) {
            // 覆盖写会同时清除可能存在的删除墓碑
            l1.put(key, Entry.ofValue(record));
        }
        return success;
    }

    @Override
    public boolean remove(SpaceKey key) {
        boolean removed = l2.remove(key);
        if (config.getTombstoneTtlMillis() > 0) {
            l1.put(key, Entry.ofTombstone(now() + config.getTombstoneTtlMillis()));
        } else {
            l1.remove(key);
        }
        return removed;
    }

    @Override
    public boolean expire(SpaceKey key, long ttlMillis) {
        boolean renewed = l2.expire(key, ttlMillis);
        if (renewed) {
            // 续期后的记录以 L2 为准，失效 L1，待下次读取回填
            l1.remove(key);
        }
        return renewed;
    }

    /**
     * 清空 L1 本地缓存（不影响 L2 数据）
     */
    public void evictAll() {
        l1.clear();
    }

    Cache<SpaceKey, Entry> getL1() {
        return l1;
    }

    private long now() {
        return clock.millis();
    }

    /**
     * L1 缓存条目：值记录或删除墓碑
     */
    public static final class Entry {

        private final KvRecord record;
        private final long tombstoneUntil;

        private Entry(KvRecord record, long tombstoneUntil) {
            this.record = record;
            this.tombstoneUntil = tombstoneUntil;
        }

        static Entry ofValue(KvRecord record) {
            return new Entry(record, 0L);
        }

        static Entry ofTombstone(long untilEpochMillis) {
            return new Entry(null, untilEpochMillis);
        }

        KvRecord getRecord() {
            return record;
        }

        boolean isEffectiveTombstone(long now) {
            return record == null && now < tombstoneUntil;
        }

        boolean isValueAlive(long now) {
            return record != null && !record.isExpired(now);
        }
    }

    /**
     * 分层存储配置
     */
    public static class Config {

        /**
         * 删除墓碑有效期（毫秒）。
         * 大于 0 时，删除操作会在 L1 写入墓碑，在该窗口内读取直接判定为不存在，
         * 不再访问 L2，也阻止同键旧值复活；窗口结束后读取自动回退到 L2。
         * 默认 0 表示不启用墓碑，删除仅失效 L1 条目。
         */
        private long tombstoneTtlMillis = 0;

        public long getTombstoneTtlMillis() {
            return tombstoneTtlMillis;
        }

        public Config setTombstoneTtlMillis(long tombstoneTtlMillis) {
            this.tombstoneTtlMillis = tombstoneTtlMillis;
            return this;
        }
    }
}
