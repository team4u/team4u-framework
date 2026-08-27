package com.team4u.framework.kv.lifecycle;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.lock.KvLock;
import com.team4u.framework.kv.lock.KvLockManager;
import com.team4u.framework.serializer.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 过期值源：缓存三大模式（cache-aside / refresh-ahead / singleflight）的声明式封装
 * <p>
 * 解决「有有效期的外部凭证」的续期问题（如第三方 access_token）：
 * 业务只需声明「值怎么取、有效期怎么算」，取值统一走 {@link #get()}：
 * </p>
 * <ul>
 *     <li>未过期：直接返回，零加载开销</li>
 *     <li>进入提前刷新窗口（refreshAhead）：本线程同步续期，其余并发请求等待
 *     （singleflight，不重复加载）；续期失败不影响返回旧值</li>
 *     <li>不存在/已过期：加载新值并写入，singleflight 保证并发下仅加载一次</li>
 * </ul>
 * <p>
 * singleflight 作用域：{@link Scope#LOCAL} 进程内去重（默认）；
 * {@link Scope#CLUSTER} 基于 {@link KvLockManager} 的 KV 锁跨实例去重
 * （需提供锁管理器，且底层存储支持 {@code CasCapable}）。
 * </p>
 *
 * @param <V> 值类型
 * @author jay.wu
 */
public class ExpiringValue<V> {

    private static final Logger log = LoggerFactory.getLogger(ExpiringValue.class);

    private final KvStore store;
    private final String space;
    private final String key;
    private final Class<V> valueType;
    private final Supplier<V> loader;
    private final TtlOf<V> ttlOf;
    private final long refreshAheadMillis;
    private final Scope scope;
    private final KvLockManager lockManager;
    private final long refreshLockMillis;
    private final long acquireTimeoutMillis;
    private final Clock clock;

    /**
     * LOCAL singleflight 的在途加载去重锁
     */
    private final ConcurrentHashMap<SpaceKey, Object> inFlight = new ConcurrentHashMap<>();

    ExpiringValue(Builder<V> b) {
        this.store = Objects.requireNonNull(b.store, "store");
        this.space = Objects.requireNonNull(b.space, "space");
        this.key = Objects.requireNonNull(b.key, "key");
        this.valueType = Objects.requireNonNull(b.valueType, "valueType");
        this.loader = Objects.requireNonNull(b.loader, "loader");
        this.ttlOf = Objects.requireNonNull(b.ttlOf, "ttlOf");
        this.refreshAheadMillis = b.refreshAheadMillis;
        this.scope = b.scope;
        this.lockManager = b.lockManager;
        this.refreshLockMillis = b.refreshLockMillis;
        this.acquireTimeoutMillis = b.acquireTimeoutMillis;
        this.clock = b.clock;

        if (scope == Scope.CLUSTER && lockManager == null) {
            throw new IllegalArgumentException(
                    "CLUSTER singleflight requires a KvLockManager");
        }
    }

    public static <V> Builder<V> builder(Class<V> valueType) {
        return new Builder<>(valueType);
    }

    /**
     * 取值：命中直接返回；进入刷新窗口同步续期；不存在则加载
     */
    public V get() {
        SpaceKey spaceKey = SpaceKey.of(space, key);
        KvRecord record = store.get(spaceKey);

        if (record == null) {
            return loadAndSave(spaceKey, "absent");
        }
        if (record.isExpired(clock.millis())) {
            return loadAndSave(spaceKey, "expired");
        }
        if (refreshAheadMillis > 0
                && record.canExpire()
                && record.getExpireAt() - clock.millis() <= refreshAheadMillis) {
            loadAndSave(spaceKey, "refreshAhead");
        }
        return decode(record);
    }

    /**
     * 强制刷新（忽略刷新窗口判断）
     *
     * @return 刷新后的新值
     */
    public V refresh() {
        return loadAndSave(SpaceKey.of(space, key), "force");
    }

    private V loadAndSave(SpaceKey spaceKey, String reason) {
        if (scope == Scope.CLUSTER) {
            return loadWithClusterLock(spaceKey, reason);
        }
        return loadWithLocalSingleflight(spaceKey, reason);
    }

    private V loadWithLocalSingleflight(SpaceKey spaceKey, String reason) {
        Object lock = inFlight.computeIfAbsent(spaceKey, k -> new Object());
        synchronized (lock) {
            try {
                // 双重检查：等待期间其他线程可能已完成加载
                if (!"force".equals(reason)) {
                    KvRecord record = store.get(spaceKey);
                    if (record != null && !record.isExpired(clock.millis())
                            && (refreshAheadMillis <= 0 || !record.canExpire()
                            || record.getExpireAt() - clock.millis() > refreshAheadMillis)) {
                        return decode(record);
                    }
                }
                return load(spaceKey, reason);
            } finally {
                inFlight.remove(spaceKey, lock);
            }
        }
    }

    private V loadWithClusterLock(SpaceKey spaceKey, String reason) {
        // 锁名禁止包含 ':'（SpaceKey 校验约束），以 '.' 连接
        String lockName = space + "." + key + ".refresh";
        // 刷新锁需覆盖加载最大耗时；正常路径释放，异常路径靠 TTL 自愈
        // （管理器心跳会对持有锁持续续约，见 KvLockManager）
        try (KvLock lock = lockManager.acquire(lockName, refreshLockMillis, acquireTimeoutMillis)) {
            if (!"force".equals(reason)) {
                KvRecord record = store.get(spaceKey);
                if (record != null && !record.isExpired(clock.millis())
                        && (refreshAheadMillis <= 0 || !record.canExpire()
                        || record.getExpireAt() - clock.millis() > refreshAheadMillis)) {
                    return decode(record);
                }
            }
            return load(spaceKey, reason);
        } catch (com.team4u.framework.kv.lock.KvLockTimeoutException e) {
            throw new IllegalStateException("Timeout waiting refresh lock: " + lockName, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to acquire refresh lock: " + lockName, e);
        }
    }

    private V load(SpaceKey spaceKey, String reason) {
        V value = loader.get();
        long ttlMillis = ttlOf.ttlOf(value);
        store.put(spaceKey, KvRecord.of(JsonUtil.toJsonStr(value), ttlMillis, clock.millis()),
                PutMode.SET);
        log.debug("ExpiringValue loaded|key={}|reason={}|ttlMs={}", spaceKey, reason, ttlMillis);
        return value;
    }

    private V decode(KvRecord record) {
        return JsonUtil.toBean(record.getValue(), valueType);
    }

    /**
     * 值的有效期计算器
     *
     * @param <V> 值类型
     * @author jay.wu
     */
    @FunctionalInterface
    public interface TtlOf<V> {

        /**
         * @return 值的有效时长（毫秒），小于等于 0 视为永不过期
         */
        long ttlOf(V value);
    }

    /**
     * singleflight 作用域
     *
     * @author jay.wu
     */
    public enum Scope {

        /**
         * 进程内去重：同 JVM 并发请求仅加载一次（默认）
         */
        LOCAL,

        /**
         * 跨实例去重：基于 KV 锁，多实例并发下全局仅一个加载者
         */
        CLUSTER
    }

    /**
     * 构建器
     *
     * @param <V> 值类型
     * @author jay.wu
     */
    public static class Builder<V> {

        private final Class<V> valueType;
        private KvStore store;
        private String space;
        private String key;
        private Supplier<V> loader;
        private TtlOf<V> ttlOf;
        private long refreshAheadMillis;
        private Scope scope = Scope.LOCAL;
        private KvLockManager lockManager;
        private long refreshLockMillis = 30_000;
        private long acquireTimeoutMillis = 30_000;
        private Clock clock = Clock.systemUTC();

        Builder(Class<V> valueType) {
            this.valueType = valueType;
        }

        public Builder<V> store(KvStore store) {
            this.store = store;
            return this;
        }

        public Builder<V> key(String space, String key) {
            this.space = space;
            this.key = key;
            return this;
        }

        /**
         * 值加载器：如何获取新值（如调用第三方接口换取 token）
         */
        public Builder<V> loader(Supplier<V> loader) {
            this.loader = loader;
            return this;
        }

        /**
         * 固定有效期（毫秒）
         */
        public Builder<V> fixedTtl(long ttlMillis) {
            return ttlOf(v -> ttlMillis);
        }

        /**
         * 按值计算有效期（毫秒），如从响应中读取 expiresIn
         */
        public Builder<V> ttlOf(TtlOf<V> ttlOf) {
            this.ttlOf = ttlOf;
            return this;
        }

        /**
         * 提前刷新窗口（毫秒）：距过期不足该窗口时，取值线程同步续期
         */
        public Builder<V> refreshAhead(long refreshAheadMillis) {
            this.refreshAheadMillis = refreshAheadMillis;
            return this;
        }

        /**
         * singleflight 作用域，默认 LOCAL
         */
        public Builder<V> scope(Scope scope) {
            this.scope = scope;
            return this;
        }

        /**
         * CLUSTER 作用域所需的锁管理器
         */
        public Builder<V> lockManager(KvLockManager lockManager) {
            this.lockManager = lockManager;
            return this;
        }

        /**
         * CLUSTER 刷新锁的租约时长（毫秒），需覆盖加载最大耗时，默认 30 秒
         */
        public Builder<V> refreshLockMillis(long refreshLockMillis) {
            this.refreshLockMillis = refreshLockMillis;
            return this;
        }

        /**
         * CLUSTER 刷新锁的阻塞获取超时（毫秒），默认 30 秒
         */
        public Builder<V> acquireTimeoutMillis(long acquireTimeoutMillis) {
            this.acquireTimeoutMillis = acquireTimeoutMillis;
            return this;
        }

        public Builder<V> clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public ExpiringValue<V> build() {
            return new ExpiringValue<>(this);
        }
    }
}
