package com.team4u.framework.kv.lifecycle;

import com.team4u.framework.base.util.Expiry;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.lock.KvLock;
import com.team4u.framework.kv.lock.KvLockManager;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 过期值源：缓存三大模式（cache-aside / refresh-ahead / singleflight）的声明式封装
 * <p>
 * 解决「有有效期的外部凭证」的续期问题（如第三方 access_token）：
 * 业务只需声明「值怎么取、有效期怎么算」，取值统一走 {@link #get()}：
 * </p>
 * <ul>
 *     <li>未过期：直接返回，零加载开销</li>
 *     <li>进入提前刷新窗口（refreshAhead）：默认本线程同步续期，其余并发请求等待
 *     （singleflight，不重复加载）；续期失败不影响返回旧值；
 *     配置 {@link Builder#refreshAheadAsync} 后改为异步续期，get() 立即返回旧值</li>
 *     <li>不存在/已过期：加载新值并写入，singleflight 保证并发下仅加载一次</li>
 * </ul>
 * <p>
 * singleflight 作用域：{@link Scope#LOCAL} 进程内去重（默认）；
 * {@link Scope#CLUSTER} 基于 {@link KvLockManager} 的 KV 锁跨实例去重
 * （需提供锁管理器，且底层存储支持 {@code CasCapable}）。
 * </p>
 * <p>
 * <b>失败冷却</b>：loader 连续失败后按指数退避推迟下一次刷新尝试——
 * 第 k 次连续失败冷却 {@code min(initial × 2^(k-1), max)}（默认 1s ~ 60s，成功即清零）。
 * 冷却仅<b>软化 refreshAhead 路径</b>：窗口内且处于冷却期时跳过加载直接返回旧值，
 * 避免源端故障时刷新窗口内的每个 get() 都打一次源（顺序请求风暴）；
 * 已过期的硬加载路径不受冷却影响（过期 + 加载失败仍抛给调用方，保持既有语义）。
 * </p>
 * <p>
 * <b>LOCAL singleflight 为 future 式</b>：并发请求通过在途
 * {@code CompletableFuture} 去重，等待者与赢家<b>共享同一次加载的结果或异常</b>，
 * 被唤醒后不再自行重试——源端故障时 N 个并发请求仅产生 1 次源端调用。
 * </p>
 * <p>
 * <b>异步 refreshAhead（swr）</b>：配置 {@link Builder#refreshAheadAsync} 后，
 * 进入刷新窗口的 get() 立即返回旧值，续期由指定 executor 异步执行
 * （必须显式提供 executor，不引入隐式共享线程池）；异步任务失败只记日志与冷却，
 * 绝不传播给调用方。异步模式下首次进入刷新窗口的调用方拿到旧值，新值在下个 get() 可见。
 * </p>
 * <p>
 * <b>与 base 组件 RefreshableValue 的分工</b>：两者都实现了「future 式 singleflight +
 * 异步提交拒绝回退 + 指数失败冷却」这一套进程内刷新语义，但状态宿主不同——
 * 本类以 KvStore 为唯一事实源（同键多实例、跨进程经存储协调，每次 get() 重读记录判新鲜），
 * 而 RefreshableValue 以内存 State 为宿主（零序列化开销、含 onChange/后台刷新等进程内能力）。
 * 因生命周期与失败传播策略深度耦合于各自的状态宿主（本类冷却仅软化刷新窗口、硬路径抛原始异常，
 * 后者冷却内嵌于读取决策、失败统一包装），两者不共享实例、不复用对方的加载框架，
 * 仅共用 base 的 {@link Expiry} 饱和时间算术。连注解可见
 * base-refresh 文档的选型提示。
 * </p>
 *
 * @param <V> 值类型
 * @author jay.wu
 */
@Slf4j
public class ExpiringValue<V> {


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
    private final long cooldownInitialMillis;
    private final long cooldownMaxMillis;
    private final Executor asyncExecutor;

    /**
     * LOCAL singleflight 的在途加载占位（本实例绑定单一 space+key，无需 Map）：
     * null 表示无在途加载；等待者与赢家共享同一 future 的结果/异常，唤醒后不再二次重试
     */
    private final AtomicReference<CompletableFuture<V>> inFlight = new AtomicReference<>();

    /**
     * 连续加载失败次数（加载成功清零），驱动失败冷却的指数退避
     */
    private volatile int consecutiveFailures;

    /**
     * 失败冷却截止时间（epoch 毫秒），0 表示不在冷却期
     */
    private volatile long retryAtMillis;

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
        this.cooldownInitialMillis = b.cooldownInitialMillis;
        this.cooldownMaxMillis = b.cooldownMaxMillis;
        this.asyncExecutor = b.refreshAheadExecutor;

        if (scope == Scope.CLUSTER && lockManager == null) {
            throw new IllegalArgumentException(
                    "CLUSTER singleflight requires a KvLockManager");
        }
        if (cooldownInitialMillis <= 0) {
            throw new IllegalArgumentException("cooldown initial must be positive");
        }
        if (cooldownMaxMillis < cooldownInitialMillis) {
            throw new IllegalArgumentException("cooldown max must be >= initial");
        }
    }

    public static <V> Builder<V> builder(Class<V> valueType) {
        return new Builder<>(valueType);
    }

    /**
     * 取值：命中直接返回；进入刷新窗口续期（默认同步、可配异步），
     * 冷却期内窗口刷新被跳过；不存在则加载
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
            // 失败冷却仅软化 refreshAhead 路径（含 CLUSTER 获取锁之前）：
            // 冷却期内跳过加载尝试直接返旧值，避免源端故障时的顺序重试风暴
            if (clock.millis() < retryAtMillis) {
                log.debug("ExpiringValue refresh ahead skipped by cooldown|key={}|retryAtMillis={}",
                        spaceKey, retryAtMillis);
                return decode(record);
            }
            if (asyncExecutor != null) {
                refreshAheadAsync(spaceKey);
            } else {
                try {
                    loadAndSave(spaceKey, "refreshAhead");
                } catch (RuntimeException e) {
                    // 续期失败不影响返回旧值：旧值在过期前仍可用，下次 get() 自动重试
                    log.warn("Refresh ahead failed, keep serving old value|key={}", spaceKey, e);
                }
            }
        }
        return decode(record);
    }

    /**
     * 强制刷新（忽略刷新窗口判断与失败冷却）
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

    /**
     * LOCAL singleflight：future 式在途去重。CAS 赢家在当前线程执行加载，
     * 输家 join 在途 future 与赢家共享同一次结果/异常（不再二次重试）
     */
    private V loadWithLocalSingleflight(SpaceKey spaceKey, String reason) {
        for (; ; ) {
            CompletableFuture<V> current = inFlight.get();
            if (current != null) {
                return joinUnwrap(current);
            }
            CompletableFuture<V> mine = new CompletableFuture<>();
            if (inFlight.compareAndSet(null, mine)) {
                return runLocalLoad(spaceKey, reason, mine);
            }
            // CAS 失败：在途任务可能刚被 finally 清空，继续循环直至等到或自己成为赢家
        }
    }

    /**
     * LOCAL 赢家的加载体：保留双重检查（等待期间其他线程可能已完成加载，
     * fresh 且非 force 直接以缓存值完成），否则执行 {@link #load}；
     * 成功完成 future，异常完成 future 后原样抛出；finally 复位在途占位
     */
    private V runLocalLoad(SpaceKey spaceKey, String reason, CompletableFuture<V> future) {
        try {
            if (!"force".equals(reason)) {
                KvRecord record = store.get(spaceKey);
                if (record != null && !record.isExpired(clock.millis())
                        && (refreshAheadMillis <= 0 || !record.canExpire()
                        || record.getExpireAt() - clock.millis() > refreshAheadMillis)) {
                    V cached = decode(record);
                    future.complete(cached);
                    return cached;
                }
            }
            V value = load(spaceKey, reason);
            future.complete(value);
            return value;
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.compareAndSet(future, null);
        }
    }

    /**
     * 等待在途加载完成：与赢家共享同一次结果/异常。
     * cause 为 RuntimeException 时原样重抛，否则包 {@link IllegalStateException}
     */
    private V joinUnwrap(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("ExpiringValue load failed: " + space + "." + key, cause);
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
        V value;
        try {
            value = loader.get();
        } catch (RuntimeException e) {
            // loader 抛异常处更新冷却状态（LOCAL / CLUSTER 路径均经由此处）
            onLoadFailure(spaceKey, e);
            throw e;
        }
        long ttlMillis = ttlOf.ttlOf(value);
        store.put(spaceKey, KvRecord.of(JsonUtil.toJsonStr(value), ttlMillis, clock.millis()),
                PutMode.SET);
        onLoadSuccess();
        log.debug("ExpiringValue loaded|key={}|reason={}|ttlMs={}", spaceKey, reason, ttlMillis);
        return value;
    }

    /**
     * 异步续期（swr）：CAS 占位成功者把加载体提交给指定 executor，get() 立即返回旧值；
     * 已有在途加载时输家直接返回旧值；提交被拒绝（如线程池已关闭）时回退占位并记 warn。
     * 异步任务内部异常只记日志与冷却，绝不传播给调用方
     */
    private void refreshAheadAsync(SpaceKey spaceKey) {
        CompletableFuture<V> future = new CompletableFuture<>();
        if (!inFlight.compareAndSet(null, future)) {
            return;
        }
        try {
            asyncExecutor.execute(() -> runAsyncLoad(spaceKey, future));
        } catch (RuntimeException e) {
            // 提交被拒绝：回退在途占位，下次 get() 再试
            inFlight.compareAndSet(future, null);
            log.warn("ExpiringValue async refresh submit rejected|key={}", spaceKey, e);
        }
    }

    /**
     * 异步加载体：失败记日志（冷却状态已在 {@link #load} 内更新），绝不传播
     */
    private void runAsyncLoad(SpaceKey spaceKey, CompletableFuture<V> future) {
        try {
            V value = scope == Scope.CLUSTER
                    ? loadWithClusterLock(spaceKey, "refreshAhead")
                    : runLocalLoad(spaceKey, "refreshAhead", future);
            future.complete(value);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
            log.warn("ExpiringValue async refresh failed|key={}", spaceKey, e);
        } finally {
            inFlight.compareAndSet(future, null);
        }
    }

    /**
     * 记录一次加载失败：推进连续失败计数，并按指数退避推迟下一次刷新尝试
     */
    private void onLoadFailure(SpaceKey spaceKey, RuntimeException error) {
        long cooldownMillis = cooldownMillis(consecutiveFailures);
        consecutiveFailures++;
        retryAtMillis = Expiry.expiryFrom(clock.millis(), cooldownMillis);
        log.debug("ExpiringValue load failed|key={}|failures={}|cooldownMs={}",
                spaceKey, consecutiveFailures, cooldownMillis, error);
    }

    /**
     * 加载成功：清零连续失败计数与冷却截止时间
     */
    private void onLoadSuccess() {
        consecutiveFailures = 0;
        retryAtMillis = 0;
    }

    /**
     * 第 k 次连续失败后的冷却时长：min(initial × 2^(k-1), max)，首次失败恰为 initial。
     * long 运算防溢出：位移量 clamp 到 30 以内，溢出或达到上限时封顶为 max
     */
    private long cooldownMillis(int consecutiveFailuresBefore) {
        long cooldown = cooldownInitialMillis << Math.min(consecutiveFailuresBefore, 30);
        if (cooldown < 0 || cooldown >= cooldownMaxMillis) {
            return cooldownMaxMillis;
        }
        return cooldown;
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
        private long cooldownInitialMillis = 1_000;
        private long cooldownMaxMillis = 60_000;
        private Executor refreshAheadExecutor;

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
         * 提前刷新窗口（毫秒）：距过期不足该窗口时，取值线程同步续期；
         * 配置 {@link #refreshAheadAsync(Executor)} 后改为异步续期
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

        /**
         * 失败冷却倍增区间（毫秒）：第 k 次连续失败后冷却
         * min(initialMillis × 2^(k-1), maxMillis)，加载成功即清零。
         * 冷却仅软化 refreshAhead 路径（窗口内跳过加载直接返回旧值），
         * 过期硬加载不受影响。默认 1000 ~ 60000，不调用本方法同样生效
         *
         * @param initialMillis 冷却下限（必须大于 0）
         * @param maxMillis     冷却上限（必须大于等于 initialMillis）
         */
        public Builder<V> cooldown(long initialMillis, long maxMillis) {
            this.cooldownInitialMillis = initialMillis;
            this.cooldownMaxMillis = maxMillis;
            return this;
        }

        /**
         * 开启异步 refreshAhead（stale-while-revalidate）：进入刷新窗口的 get() 立即返回旧值，
         * 续期提交给指定 executor 异步执行（必须显式提供，不引入隐式共享线程池），
         * 异步任务失败只记日志与冷却、绝不传播。默认 null = 维持同步续期。
         * 异步模式下首次进入刷新窗口的调用方拿到旧值，新值在下个 get() 可见
         *
         * @param executor 续期任务执行器
         */
        public Builder<V> refreshAheadAsync(Executor executor) {
            this.refreshAheadExecutor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public ExpiringValue<V> build() {
            return new ExpiringValue<>(this);
        }
    }
}
