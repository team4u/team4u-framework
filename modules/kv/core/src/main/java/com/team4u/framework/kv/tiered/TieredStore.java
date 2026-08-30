package com.team4u.framework.kv.tiered;

import com.team4u.framework.base.cache.Cache;
import com.team4u.framework.base.cache.TimedCache;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.StoreWrapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Objects;

/**
 * 分层键值存储装饰器：L1 本地缓存 + L2 远程存储
 * <p>
 * 读路径：L1 命中直接返回（不访问远端 L2）；未命中穿透到 L2，命中的记录回填 L1。
 * 写路径：写直通（write-through）—— 先写 L2，成功后同步更新 L1。
 * 删除路径：删除 L2 后在 L1 写入带有效期的<b>墓碑（tombstone）</b>，
 * 在窗口内读取直接判定为不存在（不再访问 L2），阻止同键旧值在 L2 副本
 * 同步延迟期间从 L1「死灰复燃」。
 * </p>
 *
 * <h3>并发契约（重要）</h3>
 * <ul>
 *     <li>对 L2 的单次操作由底层存储保证原子性；跨层组合（读回填、写直通、
 *     墓碑）为<b>尽力而为</b>：并发 get 回填可能与并发 remove 的墓碑交错，
 *     回填前会检查墓碑以缩小窗口，但极端交错下墓碑仍可能被晚到的回填覆盖。
 *     因此<b>强烈建议 {@code l1TtlMillis > 0}</b>，使任何陈旧数据的最长驻留
 *     时间有上界</li>
 *     <li>{@code l1TtlMillis <= 0} 且记录永不过期时，L1 无容量上限也无时间淘汰，
 *     存在无界增长与陈旧数据长期驻留风险，仅应在键集有限且无删除语义时使用</li>
 *     <li>L1 缓存操作失败（自定义 Cache 实现抛出等）时降级为<b>失效该键</b>
 *     （失效优于留旧值），不阻断 L2 已成功的写入</li>
 *     <li>L2 抛出的异常原样穿透（fail-closed），调用方可区分「键不存在」
 *     与「存储不可用」</li>
 *     <li>多实例间的 L1 失效广播不在本装饰器范围，跨实例一致性窗口为 L1 的 TTL；
 *     若通过 {@code HotSwapStore} 更换了 L2 底层存储，必须调用 {@link #evictAll()}
 *     清空本代 L1（含墓碑），避免跨代墓碑屏蔽新存储的真实数据</li>
 *     <li>即使 L1 缓存实现尚未淘汰条目，读取也会按记录自身的过期时间戳兜底判定，
 *     不会返回已过期数据</li>
 * </ul>
 * 支持能力解析（见 {@link com.team4u.framework.kv.KvStores}）：实现
 * {@link StoreWrapper} 暴露内层 L2，锁管理器等能力协商组件可穿透本装饰层。
 *
 * @author jay.wu
 */
@Slf4j
public class TieredStore implements KvStore, AutoCloseable, StoreWrapper {


    private final KvStore l2;
    private final Cache<SpaceKey, Entry> l1;
    private final Config config;
    private final Clock clock;

    /**
     * @param l2          远程（权威）存储
     * @param l1TtlMillis L1 缓存条目有效期（毫秒）；大于 0 为推荐配置，
     *                    详见类文档「并发契约」
     * @param config      分层配置（墓碑 TTL、负缓存 TTL 等）
     */
    public TieredStore(KvStore l2, long l1TtlMillis, Config config) {
        this(l2, new TimedCache<>(l1TtlMillis), config, Clock.systemUTC());
    }

    public TieredStore(KvStore l2, Cache<SpaceKey, Entry> l1, Config config, Clock clock) {
        this.l2 = Objects.requireNonNull(l2, "l2");
        this.l1 = Objects.requireNonNull(l1, "l1");
        this.config = Objects.requireNonNull(config, "config");
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
            removeL1Quietly(key);
        }

        KvRecord record = l2.get(key);
        if (record != null) {
            backfill(key, record);
        } else if (config.getNegativeTtlMillis() > 0) {
            // 负缓存：窗口内同键不存在的结果不再穿透 L2。
            // 代价：期间外部直写 L2 的数据本实例不可见
            putL1Quietly(key, Entry.ofTombstone(saturatedNowPlus(config.getNegativeTtlMillis())));
        }
        return record;
    }

    @Override
    public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
        boolean success = l2.put(key, record, mode);
        if (success) {
            // 覆盖写会同时清除可能存在的删除墓碑
            putL1Quietly(key, Entry.ofValue(record));
        }
        return success;
    }

    @Override
    public boolean remove(SpaceKey key) {
        boolean removed = l2.remove(key);
        if (config.getTombstoneTtlMillis() > 0) {
            putL1Quietly(key, Entry.ofTombstone(saturatedNowPlus(config.getTombstoneTtlMillis())));
        } else {
            removeL1Quietly(key);
        }
        return removed;
    }

    @Override
    public boolean expire(SpaceKey key, long ttlMillis) {
        boolean renewed = l2.expire(key, ttlMillis);
        if (renewed) {
            // 续期后的记录以 L2 为准，失效 L1，待下次读取回填；
            // 键不存在时（renewed=false）保留墓碑等既有 L1 状态
            removeL1Quietly(key);
        }
        return renewed;
    }

    @Override
    public KvStore unwrap() {
        return l2;
    }

    /**
     * 清空 L1 本地缓存（不影响 L2 数据）。
     * 通过 HotSwapStore 更换 L2 底层存储后必须调用。
     */
    public void evictAll() {
        try {
            l1.clear();
        } catch (RuntimeException e) {
            log.warn("Failed to clear L1 cache", e);
        }
    }

    /**
     * 关闭：清空 L1 后级联静默关闭 L2（尽力而为，关闭异常记 warn 不抛出）。
     * 内层 L2 被多方共享时不要关闭本装饰器——谁创建整棵洋葱谁负责关闭
     */
    @Override
    public void close() {
        evictAll();
        KvStores.closeQuietly(l2);
    }

    /**
     * 回填前检查墓碑：若已有有效删除墓碑则放弃回填，缩小「晚到回填覆盖墓碑」的竞态窗口
     */
    private void backfill(SpaceKey key, KvRecord record) {
        Entry existing = l1.get(key);
        if (existing != null && existing.isEffectiveTombstone(now())) {
            return;
        }
        putL1Quietly(key, Entry.ofValue(record));
    }

    /**
     * L1 写入失败时降级为失效该键：失效优于留旧值
     */
    private void putL1Quietly(SpaceKey key, Entry entry) {
        try {
            l1.put(key, entry);
        } catch (RuntimeException e) {
            log.warn("L1 put failed, degrade to evict|key={}", key, e);
            removeL1Quietly(key);
        }
    }

    private void removeL1Quietly(SpaceKey key) {
        try {
            l1.remove(key);
        } catch (RuntimeException e) {
            log.warn("L1 remove failed|key={}", key, e);
        }
    }

    private long saturatedNowPlus(long ttlMillis) {
        long until = now() + ttlMillis;
        return until < 0 ? Long.MAX_VALUE : until;
    }

    private long now() {
        return clock.millis();
    }

    /**
     * L1 缓存条目：值记录或删除墓碑
     */
    @lombok.Getter
    public static final class Entry {

        private final KvRecord record;
        private final long tombstoneUntil;

        private Entry(KvRecord record, long tombstoneUntil) {
            this.record = record;
            this.tombstoneUntil = tombstoneUntil;
        }

        public static Entry ofValue(KvRecord record) {
            return new Entry(record, 0L);
        }

        public static Entry ofTombstone(long untilEpochMillis) {
            return new Entry(null, untilEpochMillis);
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
     *
     * @author jay.wu
     */
    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    public static class Config {

        /**
         * 删除墓碑默认有效期（毫秒）：0 表示不启用墓碑
         */
        public static final long DEFAULT_TOMBSTONE_TTL_MILLIS = 0;

        /**
         * 负缓存默认有效期（毫秒）：0 表示不缓存「不存在」结果
         */
        public static final long DEFAULT_NEGATIVE_TTL_MILLIS = 0;

        /**
         * 删除墓碑有效期（毫秒）。
         * 大于 0 时，删除操作会在 L1 写入墓碑，在该窗口内读取直接判定为不存在，
         * 不再访问 L2，也阻止同键旧值复活（尽力而为，见类文档「并发契约」）；
         * 窗口结束后读取自动回退到 L2。
         */
        private long tombstoneTtlMillis = DEFAULT_TOMBSTONE_TTL_MILLIS;

        /**
         * 负缓存有效期（毫秒）。
         * 大于 0 时，L2 未命中的键会在 L1 写入墓碑，窗口内同键读取不再穿透 L2。
         * 适合防穿透场景；代价是期间外部直写 L2 的数据本实例不可见。
         */
        private long negativeTtlMillis = DEFAULT_NEGATIVE_TTL_MILLIS;
    }
}
