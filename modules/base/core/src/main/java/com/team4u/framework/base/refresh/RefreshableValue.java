package com.team4u.framework.base.refresh;

import com.team4u.framework.base.util.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 支持软死期 / 硬死期 / 失败冷却的单值刷新缓存。
 * <p>
 * 核心是三个时间戳（均基于 {@link Clock#millis()}）：
 * <ul>
 *   <li><b>staleAfter（软死期）</b>：值开始"过期"的时间点，到达后按读取策略触发刷新，但旧值仍可返回。
 *       由 refreshEvery / ttlOf 计算得出：staleAfter = loadedAt + ttl；配置 refreshAhead 时为
 *       loadedAt + max(ttl - refreshAhead, 0)；ttlOf 返回的 ttl &lt;= 0 视为永不过期（Long.MAX_VALUE）；
 *       无任何 freshness 配置（MANUAL 模式）时同样为 Long.MAX_VALUE，仅显式 {@link #refresh()} 改变值。</li>
 *   <li><b>hardAfter（硬死期）</b>：staleAfter + maxStale（未配置 maxStale 时为 Long.MAX_VALUE）。
 *       到达后旧值不再可信，读取将阻塞重载并把失败异常抛给调用方——maxStale 是正确性边界。</li>
 *   <li><b>retryAt（失败冷却）</b>：连续失败后的重试截止时间点。第 k 次连续失败后
 *       retryAt = now + min(cooldownInitial × 2^(k-1), cooldownMax)（long 运算防溢出），成功后清零。
 *       冷却期内读请求直接返回旧值，避免打垮源端。</li>
 * </ul>
 * <p>
 * {@link #get()} 决策算法（严格按序）：
 * <pre>
 * s = state.get(); now = clock.millis()
 * ① s.value == null 且未 close   → 阻塞加载（所有模式一致，get() 永不返回首载前的 null）
 * ② now &lt; s.staleAfter           → 返回 s.value（热路径：一次 volatile 读 + 比较，分配开销较低）
 * ③ 已 close                     → 有值返值；未加载抛 IllegalStateException
 * ④ now &gt;= hardAfter(s)          → 阻塞重载，绕过冷却；失败异常抛给调用方
 * ⑤ now &lt; s.retryAt              → 返回 s.value（冷却兜底，不打源端）
 * ⑥ staleWhileRevalidate 开启    → 触发异步刷新，立即返回 s.value
 * ⑦ 否则                         → 阻塞刷新
 * </pre>
 * <p>
 * <b>并发契约</b>：
 * <ul>
 *   <li>单写者纪律：同一时刻至多一个加载在途（singleflight），因此状态迁移仅由加载线程执行
 *       {@code state.set(...)}，读方一次 volatile 读即得到原子快照。</li>
 *   <li>在途互斥：通过 {@code AtomicReference<CompletableFuture<Void>> inFlight} 实现。
 *       同步入口（get①④⑦ / refresh / warmup）CAS(null→f) 赢家在当前线程执行加载，输家 join(f)，
 *       等待者共享同一结果 / 异常（等待者不二次重试）；CAS 失败后 inFlight 可能已被 finally 清空，
 *       故以 for(;;) 循环直到 join 到或自己成为赢家。异步入口（staleWhileRevalidate⑥ / 后台 tick）
 *       CAS 赢家把加载体提交给调度器执行，输家直接返回；提交被拒绝（RejectedExecutionException）时
 *       CAS 回退、记日志、下次再试。finally 中 CAS 复位 inFlight，保证一次异常后不会永久停刷。</li>
 *   <li>状态迁移：成功时更新 value/version/loadedAt/staleAfter/refreshCount 并清零 failures/retryAt/lastError，
 *       equals 未变时不发布新值引用（保留旧实例）、version 不动、不触发 onChange；
 *       失败时 value/version/loadedAt/staleAfter/refreshCount 全部原样保留（失败不延长旧值死期），
 *       仅累加 failures/failureCount、按冷却公式推进 retryAt 并记录 lastError。</li>
 *   <li>onChange：仅在 version 递增时触发，提交到静态懒加载单线程 daemon 线程池
 *       （team4u-refresh-callback）异步隔离执行，逐回调 try/catch 记 warn；
 *       单加载线程按序提交 + 单消费线程 = 每个值的事件天然 FIFO。submit 发生在 state.set 之后。</li>
 *   <li>后台任务：background() 且配置 freshness 时以 scheduleWithFixedDelay 周期运行 tick
 *       （period = refreshEvery 或 refreshAhead），tick 整体 catch Throwable
 *       （ScheduledExecutorService 任务抛异常会静默取消后续执行），条件满足则触发异步刷新。</li>
 *   <li>close()：CAS 幂等；置位后 cancel 后台任务；不等待在途（在途加载自然完成，允许最后一次发布）。</li>
 * </ul>
 * <p>
 * <b>JMM 链</b>：加载线程 state.set（volatile 写）先行发生于 future.complete，
 * 阻塞方经 future 的 happens-before 看到新状态，非阻塞方经 state 的 volatile 读看到。
 *
 * @param <T> 值类型
 * @author jay.wu
 */
@Slf4j
public final class RefreshableValue<T> implements Supplier<T>, AutoCloseable {

    /**
     * 默认失败冷却下限
     */
    private static final Duration DEFAULT_COOLDOWN_INITIAL = Duration.ofSeconds(1);
    /**
     * 默认失败冷却上限
     */
    private static final Duration DEFAULT_COOLDOWN_MAX = Duration.ofSeconds(60);

    /**
     * 共享调度器线程名序号
     */
    private static final AtomicInteger SHARED_SCHEDULER_THREAD_COUNTER = new AtomicInteger();

    /**
     * 共享懒加载调度器（2 线程、daemon、命名 team4u-refresh-N），未显式指定 scheduler 时使用
     */
    private static volatile ScheduledExecutorService sharedScheduler;

    /**
     * onChange 回调静态懒加载单线程 daemon 线程池（命名 team4u-refresh-callback）
     */
    private static volatile ExecutorService callbackExecutor;

    private final String name;
    private final Loader<T> loader;
    /**
     * 固定周期软死期（毫秒），与 ttlOf 互斥，null 表示未配置
     */
    private final Long refreshEveryMillis;
    /**
     * 按值计算软死期的函数，null 表示未配置
     */
    private final Function<T, Duration> ttlOf;
    /**
     * 提前刷新窗口（毫秒），仅配合 ttlOf，null 表示未配置
     */
    private final Long refreshAheadMillis;
    /**
     * 硬死期余量（毫秒），null 表示无限（Long.MAX_VALUE）
     */
    private final Long maxStaleMillis;
    private final boolean staleWhileRevalidate;
    private final long cooldownInitialMillis;
    private final long cooldownMaxMillis;
    private final List<BiConsumer<T, T>> onChangeHandlers;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    /**
     * 不可变状态快照，单次 volatile 读即为原子快照
     */
    private final AtomicReference<State<T>> state = new AtomicReference<>(State.<T>initial());
    /**
     * 在途加载互斥（singleflight）：null 表示无在途加载
     */
    private final AtomicReference<CompletableFuture<Void>> inFlight = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /**
     * 后台定时任务句柄（无 background 时为 null），close 时 cancel(false)
     */
    private final ScheduledFuture<?> tickFuture;

    private RefreshableValue(Builder<T> builder) {
        validate(builder);

        this.name = builder.name;
        this.loader = builder.loader;
        this.refreshEveryMillis = builder.refreshEvery == null ? null : builder.refreshEvery.toMillis();
        this.ttlOf = builder.ttlOf;
        this.refreshAheadMillis = builder.refreshAhead == null ? null : builder.refreshAhead.toMillis();
        this.maxStaleMillis = builder.maxStale == null ? null : builder.maxStale.toMillis();
        this.staleWhileRevalidate = builder.staleWhileRevalidate;
        this.cooldownInitialMillis = builder.cooldownInitial.toMillis();
        this.cooldownMaxMillis = builder.cooldownMax.toMillis();
        this.onChangeHandlers = Collections.unmodifiableList(new ArrayList<>(builder.onChangeHandlers));
        this.clock = builder.clock;
        this.scheduler = builder.scheduler != null ? builder.scheduler : sharedScheduler();

        if (builder.background) {
            long periodMillis = refreshEveryMillis != null ? refreshEveryMillis : refreshAheadMillis;
            this.tickFuture = scheduler.scheduleWithFixedDelay(
                    this::tick, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        } else {
            this.tickFuture = null;
        }
    }

    /**
     * 创建构建器
     *
     * @param <T> 值类型
     * @return 构建器
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * 读取当前值，按类注释中的决策算法执行
     *
     * @return 当前值（首载完成前会阻塞，永不返回首载前的 null）
     */
    @Override
    public T get() {
        State<T> s = state.get();

        // ① 未加载：阻塞加载（closed 时交由 ③ 处理）
        if (s.value == null && !closed.get()) {
            loadSync();
            return state.get().value;
        }

        long now = clock.millis();

        // ② 热路径：未过软死期直接返回
        if (now < s.staleAfter) {
            return s.value;
        }

        // ③ 已关闭：有值返值；未加载抛异常
        if (closed.get()) {
            if (s.value != null) {
                return s.value;
            }
            throw new IllegalStateException("RefreshableValue is closed and never loaded: " + name);
        }

        // ④ 硬死期：阻塞重载，绕过冷却；失败异常抛给调用方
        if (now >= hardAfter(s)) {
            loadSync();
            return state.get().value;
        }

        // ⑤ 失败冷却期内：返回旧值，不打源端
        if (now < s.retryAt) {
            return s.value;
        }

        // ⑥ stale-while-revalidate：触发异步刷新，立即返回旧值
        if (staleWhileRevalidate) {
            loadAsync();
            return s.value;
        }

        // ⑦ 阻塞刷新
        loadSync();
        return state.get().value;
    }

    /**
     * 获取当前内存值，未加载返回 null，永不触发刷新
     *
     * @return 当前内存值，可能为 null
     */
    public T peek() {
        return state.get().value;
    }

    /**
     * 是否已过软死期（已加载且 now >= staleAfter）
     *
     * @return true 表示已加载且已过期
     */
    public boolean isStale() {
        State<T> s = state.get();
        return s.value != null && clock.millis() >= s.staleAfter;
    }

    /**
     * 显式同步刷新：绕过冷却与过期判断强制加载。
     * <p>
     * 在途加载存在时合并等待；失败抛 {@link IllegalStateException}（cause 保留）；
     * closed 后抛 {@link IllegalStateException}。
     */
    public void refresh() {
        if (closed.get()) {
            throw new IllegalStateException("RefreshableValue is closed: " + name);
        }
        loadSync();
    }

    /**
     * 获取当前状态快照（单次 volatile 读构建的不可变快照）
     *
     * @return 状态快照
     */
    public Status status() {
        State<T> s = state.get();
        long now = clock.millis();
        boolean loaded = s.value != null;
        boolean stale = loaded && now >= s.staleAfter;
        long staleMillis = stale ? now - s.staleAfter : 0L;
        return new Status(name, closed.get(), loaded, s.version, s.loadedAt, s.staleAfter,
                stale, staleMillis, hardAfter(s), s.retryAt, s.failures,
                s.refreshCount, s.failureCount, s.lastError);
    }

    /**
     * 关闭：幂等；停后台任务；不等待在途（在途加载自然完成，其结果允许最后一次发布）。
     * close 后 {@link #get()} 行为见决策算法③。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (tickFuture != null) {
            tickFuture.cancel(false);
        }
        log.debug("RefreshableValue closed|name={}", name);
    }

    // ---------------------------------------------------------------- 加载入口

    /**
     * 同步加载入口：CAS 赢家在当前线程执行加载，输家 join 在途 future（共享同一结果/异常）
     */
    private void loadSync() {
        CompletableFuture<Void> mine = new CompletableFuture<>();
        for (; ; ) {
            CompletableFuture<Void> current = inFlight.get();
            if (current != null) {
                joinInFlight(current);
                return;
            }
            if (inFlight.compareAndSet(null, mine)) {
                runLoad(mine, true);
                return;
            }
            // CAS 失败：在途任务可能刚被 finally 清空，继续循环直至 join 到或自己成为赢家
        }
    }

    /**
     * 异步加载入口（staleWhileRevalidate⑥ / 后台 tick）：赢家提交给调度器，输家直接返回
     */
    private void loadAsync() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!inFlight.compareAndSet(null, future)) {
            return;
        }
        try {
            scheduler.execute(() -> runLoad(future, false));
        } catch (RejectedExecutionException e) {
            // 提交被拒绝：回退在途标记，下次再试
            inFlight.compareAndSet(future, null);
            log.warn("RefreshableValue async refresh submit rejected|name={}", name, e);
        }
    }

    /**
     * 等待在途加载完成，失败时与赢家共享同一异常
     */
    private void joinInFlight(CompletableFuture<Void> future) {
        try {
            future.join();
        } catch (CompletionException e) {
            throw loadFailure(e.getCause() != null ? e.getCause() : e);
        }
    }

    /**
     * 唯一加载体执行框架：加载 → complete/completeExceptionally → finally 复位在途标记
     *
     * @param future           在途 future
     * @param propagateFailure true 时（同步路径）失败异常抛给调用方
     */
    private void runLoad(CompletableFuture<Void> future, boolean propagateFailure) {
        try {
            doLoad();
            future.complete(null);
        } catch (Throwable t) {
            future.completeExceptionally(t);
            if (propagateFailure) {
                throw loadFailure(t);
            }
        } finally {
            inFlight.compareAndSet(future, null);
        }
    }

    /**
     * 同步路径失败包装：IllegalStateException（cause 保留）；
     * loader 返回 null 等契约性 IllegalArgumentException 原样抛出
     */
    private RuntimeException loadFailure(Throwable t) {
        if (t instanceof IllegalArgumentException) {
            return (IllegalArgumentException) t;
        }
        return new IllegalStateException("RefreshableValue load failed: " + name, t);
    }

    /**
     * 唯一加载体：loader 调用 + 状态迁移（成功发布 / 失败记录）
     */
    private void doLoad() throws Throwable {
        State<T> s = state.get();
        LoadContext<T> context = new LoadContextImpl<>(s.value, s.failures);
        long now = clock.millis();

        T newValue;
        long staleAfter;
        try {
            newValue = loader.load(context);
            if (newValue == null) {
                throw new IllegalArgumentException("Loader must not return null: " + name);
            }
            staleAfter = computeStaleAfter(newValue, now);
        } catch (Throwable t) {
            onLoadFailure(s, t, clock.millis());
            throw t;
        }

        onLoadSuccess(s, newValue, staleAfter, now);
    }

    // ---------------------------------------------------------------- 状态迁移

    /**
     * 成功迁移：equals 未变时不发布新值引用、version 不动、不触发 onChange
     */
    private void onLoadSuccess(State<T> s, T newValue, long staleAfter, long now) {
        boolean changed = !Objects.equals(s.value, newValue);
        T published = changed ? newValue : s.value;
        int newVersion = changed ? s.version + 1 : s.version;

        state.set(new State<>(published, newVersion, now, staleAfter,
                0L, 0, s.refreshCount + 1, s.failureCount, null));

        if (changed) {
            log.info("RefreshableValue changed|name={}|version={}", name, newVersion);
            fireOnChange(s.value, newValue);
        }
    }

    /**
     * 失败迁移：旧值与其死期原样保留（失败不延长旧值死期），仅推进失败计数与冷却
     */
    private void onLoadFailure(State<T> s, Throwable error, long now) {
        int failures = s.failures + 1;
        long cooldownMillis = cooldownMillis(s.failures);
        state.set(new State<>(s.value, s.version, s.loadedAt, s.staleAfter,
                saturatedAdd(now, cooldownMillis), failures,
                s.refreshCount, s.failureCount + 1, error));
        log.warn("RefreshableValue load failed|name={}|failures={}|cooldownMs={}",
                name, failures, cooldownMillis, error);
    }

    /**
     * 第 k 次连续失败后的冷却时长：min(initial × 2^(k-1), max)，k-1 为本次失败前的连续失败次数。
     * long 运算防溢出，溢出或达到上限时封顶为 max。
     */
    private long cooldownMillis(int consecutiveFailuresBefore) {
        long max = cooldownMaxMillis;
        long cooldown = cooldownInitialMillis;
        for (int i = 0; i < consecutiveFailuresBefore; i++) {
            cooldown *= 2;
            if (cooldown < 0 || cooldown >= max) {
                return max;
            }
        }
        return Math.min(cooldown, max);
    }

    /**
     * 计算软死期：refreshEvery 固定周期；ttlOf 按值计算（ttl<=0 永不过期），
     * 配置 refreshAhead 时为 loadedAt + max(ttl - refreshAhead, 0)
     */
    private long computeStaleAfter(T value, long now) {
        if (refreshEveryMillis != null) {
            return saturatedAdd(now, refreshEveryMillis);
        }
        if (ttlOf == null) {
            // MANUAL 模式：永不过期，仅显式 refresh() 改变值
            return Long.MAX_VALUE;
        }
        Duration ttl = ttlOf.apply(value);
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Long.MAX_VALUE;
        }
        long ttlMillis = ttl.toMillis();
        long effectiveTtlMillis = refreshAheadMillis == null
                ? ttlMillis
                : Math.max(ttlMillis - refreshAheadMillis, 0L);
        return saturatedAdd(now, effectiveTtlMillis);
    }

    /**
     * 硬死期：staleAfter + maxStale（未配置 maxStale 或未加载时为 Long.MAX_VALUE）
     */
    private long hardAfter(State<T> s) {
        if (s.value == null || maxStaleMillis == null || s.staleAfter == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return saturatedAdd(s.staleAfter, maxStaleMillis);
    }

    /**
     * 非负饱和加法，溢出时返回 Long.MAX_VALUE
     */
    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    // ---------------------------------------------------------------- onChange 回调

    /**
     * 变更回调：提交到静态单线程 daemon 线程池异步隔离执行，逐回调 try/catch 记 warn。
     * submit 发生在 state.set 之后；单加载线程按序提交 + 单消费线程 = 每值事件天然 FIFO。
     */
    private void fireOnChange(T oldValue, T newValue) {
        if (onChangeHandlers.isEmpty()) {
            return;
        }
        try {
            callbackExecutor().execute(() -> {
                for (BiConsumer<T, T> handler : onChangeHandlers) {
                    try {
                        handler.accept(oldValue, newValue);
                    } catch (Throwable t) {
                        log.warn("RefreshableValue onChange callback failed|name={}", name, t);
                    }
                }
            });
        } catch (Throwable t) {
            log.warn("RefreshableValue onChange submit failed|name={}", name, t);
        }
    }

    // ---------------------------------------------------------------- 后台任务

    /**
     * 后台周期任务：满足条件时触发异步刷新。
     * 必须整体 catch Throwable——ScheduledExecutorService 任务抛异常会静默取消后续执行。
     */
    private void tick() {
        try {
            State<T> s = state.get();
            long now = clock.millis();
            if (!closed.get() && s.value != null && now >= s.staleAfter && now >= s.retryAt) {
                loadAsync();
            }
        } catch (Throwable t) {
            log.warn("RefreshableValue background tick failed|name={}", name, t);
        }
    }

    // ---------------------------------------------------------------- 共享线程池

    private static synchronized ScheduledExecutorService sharedScheduler() {
        if (sharedScheduler == null) {
            sharedScheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread thread = new Thread(r, "team4u-refresh-" + SHARED_SCHEDULER_THREAD_COUNTER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        return sharedScheduler;
    }

    private static synchronized ExecutorService callbackExecutor() {
        if (callbackExecutor == null) {
            callbackExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "team4u-refresh-callback");
                thread.setDaemon(true);
                return thread;
            });
        }
        return callbackExecutor;
    }

    // ---------------------------------------------------------------- 校验

    private static <T> void validate(Builder<T> builder) {
        Assert.notBlank(builder.name, "name must not be blank");
        Assert.notNull(builder.loader, "loader must not be null: " + builder.name);

        boolean hasFreshness = builder.refreshEvery != null || builder.ttlOf != null;

        Assert.isTrue(!(builder.refreshEvery != null && builder.ttlOf != null),
                "refreshEvery and ttlOf are mutually exclusive: " + builder.name);
        Assert.isTrue(builder.refreshAhead == null || builder.ttlOf != null,
                "refreshAhead requires ttlOf: " + builder.name);
        Assert.isTrue(builder.maxStale == null || hasFreshness,
                "maxStale requires refreshEvery or ttlOf: " + builder.name);
        Assert.isTrue(!builder.background || hasFreshness,
                "background requires refreshEvery or ttlOf: " + builder.name);
        Assert.isTrue(!(builder.background && builder.ttlOf != null && builder.refreshAhead == null),
                "background with ttlOf requires refreshAhead: " + builder.name);
        Assert.isTrue(!(builder.ttlOf != null && builder.staleWhileRevalidate && builder.maxStale == null),
                "staleWhileRevalidate with ttlOf requires maxStale: " + builder.name);

        if (builder.refreshEvery != null) {
            Assert.isTrue(isPositive(builder.refreshEvery), "refreshEvery must be positive: " + builder.name);
        }
        if (builder.refreshAhead != null) {
            Assert.isTrue(isPositive(builder.refreshAhead), "refreshAhead must be positive: " + builder.name);
        }
        if (builder.maxStale != null) {
            Assert.isTrue(isPositive(builder.maxStale), "maxStale must be positive: " + builder.name);
        }
        Assert.isTrue(isPositive(builder.cooldownInitial), "cooldown initial must be positive: " + builder.name);
        Assert.isTrue(builder.cooldownMax.compareTo(builder.cooldownInitial) >= 0,
                "cooldown max must be >= initial: " + builder.name);
    }

    private static boolean isPositive(Duration duration) {
        return !duration.isNegative() && !duration.isZero();
    }

    // ---------------------------------------------------------------- 嵌套类型

    /**
     * 值加载器
     *
     * @param <T> 值类型
     * @author jay.wu
     */
    @FunctionalInterface
    public interface Loader<T> {

        /**
         * 加载新值
         *
         * @param context 加载上下文（上次成功值与本次尝试前的连续失败次数）
         * @return 新值，不得返回 null（返回 null 将抛 IllegalArgumentException）
         * @throws Exception 加载失败
         */
        T load(LoadContext<T> context) throws Exception;
    }

    /**
     * 加载上下文
     *
     * @param <T> 值类型
     * @author jay.wu
     */
    public interface LoadContext<T> {

        /**
         * @return 上次成功值，首载为 null
         */
        T oldValue();

        /**
         * @return 本次尝试前的连续失败次数，首次为 0
         */
        int attempt();
    }

    private static final class LoadContextImpl<T> implements LoadContext<T> {
        private final T oldValue;
        private final int attempt;

        private LoadContextImpl(T oldValue, int attempt) {
            this.oldValue = oldValue;
            this.attempt = attempt;
        }

        @Override
        public T oldValue() {
            return oldValue;
        }

        @Override
        public int attempt() {
            return attempt;
        }
    }

    /**
     * 状态快照（不可变），由单次 volatile 读构建
     *
     * @author jay.wu
     */
    @Getter
    public static final class Status {
        private final String name;
        private final boolean closed;
        private final boolean loaded;
        private final int version;
        private final long loadedAtMillis;
        private final long staleAfterMillis;
        private final boolean stale;
        /**
         * 未过期为 0，否则 now - staleAfter
         */
        private final long staleMillis;
        private final long hardAfterMillis;
        private final long retryAtMillis;
        private final int consecutiveFailures;
        private final long refreshCount;
        private final long failureCount;
        private final Throwable lastError;

        private Status(String name, boolean closed, boolean loaded, int version, long loadedAtMillis,
                       long staleAfterMillis, boolean stale, long staleMillis, long hardAfterMillis,
                       long retryAtMillis, int consecutiveFailures, long refreshCount, long failureCount,
                       Throwable lastError) {
            this.name = name;
            this.closed = closed;
            this.loaded = loaded;
            this.version = version;
            this.loadedAtMillis = loadedAtMillis;
            this.staleAfterMillis = staleAfterMillis;
            this.stale = stale;
            this.staleMillis = staleMillis;
            this.hardAfterMillis = hardAfterMillis;
            this.retryAtMillis = retryAtMillis;
            this.consecutiveFailures = consecutiveFailures;
            this.refreshCount = refreshCount;
            this.failureCount = failureCount;
            this.lastError = lastError;
        }
    }

    /**
     * 不可变内部状态（全部 final 字段），value 为 null 表示未加载
     *
     * @param <T> 值类型
     */
    private static final class State<T> {
        private final T value;
        /**
         * 值实际变更次数
         */
        private final int version;
        private final long loadedAt;
        private final long staleAfter;
        private final long retryAt;
        /**
         * 连续失败次数
         */
        private final int failures;
        private final long refreshCount;
        private final long failureCount;
        private final Throwable lastError;

        private State(T value, int version, long loadedAt, long staleAfter, long retryAt,
                      int failures, long refreshCount, long failureCount, Throwable lastError) {
            this.value = value;
            this.version = version;
            this.loadedAt = loadedAt;
            this.staleAfter = staleAfter;
            this.retryAt = retryAt;
            this.failures = failures;
            this.refreshCount = refreshCount;
            this.failureCount = failureCount;
            this.lastError = lastError;
        }

        private static <T> State<T> initial() {
            return new State<>(null, 0, 0L, 0L, 0L, 0, 0L, 0L, null);
        }
    }

    /**
     * 构建器（手写，含 build 校验）
     *
     * @param <T> 值类型
     * @author jay.wu
     */
    public static final class Builder<T> {

        private String name;
        private Loader<T> loader;
        private Duration refreshEvery;
        private Function<T, Duration> ttlOf;
        private Duration refreshAhead;
        private Duration maxStale;
        private boolean staleWhileRevalidate;
        private boolean background;
        private boolean warmup;
        private Duration cooldownInitial = DEFAULT_COOLDOWN_INITIAL;
        private Duration cooldownMax = DEFAULT_COOLDOWN_MAX;
        private final List<BiConsumer<T, T>> onChangeHandlers = new ArrayList<>();
        private Clock clock = Clock.systemUTC();
        private ScheduledExecutorService scheduler;

        /**
         * 设置名称（必填）
         *
         * @param name 名称
         * @return this
         */
        public Builder<T> name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置加载器（必填）
         *
         * @param loader 加载器
         * @return this
         */
        public Builder<T> loader(Loader<T> loader) {
            this.loader = loader;
            return this;
        }

        /**
         * 设置固定周期软死期，与 {@link #ttlOf(Function)} 互斥
         *
         * @param refreshEvery 固定周期（必须大于 0）
         * @return this
         */
        public Builder<T> refreshEvery(Duration refreshEvery) {
            this.refreshEvery = refreshEvery;
            return this;
        }

        /**
         * 设置按值软死期（如 token 的 expiresIn），与 {@link #refreshEvery(Duration)} 互斥。
         * 返回的 ttl 小于等于 0 视为永不过期。
         *
         * @param ttlOf ttl 计算函数
         * @return this
         */
        public Builder<T> ttlOf(Function<T, Duration> ttlOf) {
            this.ttlOf = ttlOf;
            return this;
        }

        /**
         * 设置提前刷新窗口，仅配合 {@link #ttlOf(Function)} 使用：
         * staleAfter = loadedAt + max(ttl - refreshAhead, 0)
         *
         * @param refreshAhead 提前刷新窗口（必须大于 0）
         * @return this
         */
        public Builder<T> refreshAhead(Duration refreshAhead) {
            this.refreshAhead = refreshAhead;
            return this;
        }

        /**
         * 设置硬死期余量：hardAfter = staleAfter + maxStale，默认无限（Long.MAX_VALUE）。
         * 需已配置 freshness（refreshEvery 或 ttlOf）。
         *
         * @param maxStale 硬死期余量（必须大于 0）
         * @return this
         */
        public Builder<T> maxStale(Duration maxStale) {
            this.maxStale = maxStale;
            return this;
        }

        /**
         * 开启 stale-while-revalidate：读时遇过期立即返回旧值并触发异步刷新，默认关闭。
         * 配合 ttlOf 使用时必须提供 {@link #maxStale(Duration)}。
         *
         * @return this
         */
        public Builder<T> staleWhileRevalidate() {
            this.staleWhileRevalidate = true;
            return this;
        }

        /**
         * 开启后台定时刷新（无人读也保持最新），默认关闭。
         * 需已配置 freshness；配合 ttlOf 使用时必须提供 {@link #refreshAhead(Duration)}（后台 tick 需要周期）。
         *
         * @return this
         */
        public Builder<T> background() {
            this.background = true;
            return this;
        }

        /**
         * 开启 warmup：build 时同步加载一次，失败异常从 build() 抛出，默认关闭
         *
         * @return this
         */
        public Builder<T> warmup() {
            this.warmup = true;
            return this;
        }

        /**
         * 设置失败冷却倍增区间（initial × 2^(k-1)，封顶 max），默认 1s ~ 60s
         *
         * @param initial 冷却下限（必须大于 0）
         * @param max     冷却上限（必须大于等于 initial）
         * @return this
         */
        public Builder<T> cooldown(Duration initial, Duration max) {
            this.cooldownInitial = initial;
            this.cooldownMax = max;
            return this;
        }

        /**
         * 追加变更回调（可多次调用累积）：仅值实际变更（Objects.equals 判定）时触发，异步隔离执行
         *
         * @param onChange 回调，参数为 (旧值, 新值)
         * @return this
         */
        public Builder<T> onChange(BiConsumer<T, T> onChange) {
            this.onChangeHandlers.add(Objects.requireNonNull(onChange, "onChange"));
            return this;
        }

        /**
         * 设置时钟，默认 Clock.systemUTC()
         *
         * @param clock 时钟
         * @return this
         */
        public Builder<T> clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * 设置自定义调度器（调用方所有，close 不会 shutdown），
         * 默认共享懒加载 ScheduledThreadPoolExecutor（2 线程、daemon、命名 team4u-refresh-N）
         *
         * @param scheduler 调度器
         * @return this
         */
        public Builder<T> scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * 校验并构造；warmup 时同步加载（失败异常从本方法抛出）
         *
         * @return RefreshableValue 实例
         * @throws IllegalArgumentException 配置非法
         */
        public RefreshableValue<T> build() {
            RefreshableValue<T> value = new RefreshableValue<>(this);
            if (warmup) {
                value.loadSync();
            }
            return value;
        }
    }
}
