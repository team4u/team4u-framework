package com.team4u.framework.kv.hotswap;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.StoreWrapper;
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.core.ProxyException;
import com.team4u.framework.proxy.support.Swappable;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 热交换键值存储：运行时原子替换底层存储，业务持有的引用不变
 * <p>
 * 基于 {@code ProxyBuilder} 的热交换能力（volatile delegate 替换，对所有线程立即可见）实现，
 * 任意 {@link KvStore} 实现均可包装，无需继承特定基类。适用于在线切换后端、
 * 存储迁移、故障转移等场景。
 * </p>
 * <p>
 * Safe Swap 约定：调用方应先构建并验证新存储可用，再执行交换；
 * 交换下来的旧存储若实现了 {@link AutoCloseable}，默认在交换成功后静默关闭
 * （尽力而为，不阻塞交换流程，关闭异常仅记录日志）。
 * 注意：交换瞬间在途调用仍持有旧存储引用，立即关闭可能中断在途调用——
 * 连接池型后端建议使用 {@link #swap(KvStore, KvStore, long)} 的宽限期重载。
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
public final class HotSwapStore {


    /**
     * 延迟关闭的共享守护线程调度器：不阻塞业务线程，不阻止 JVM 退出
     */
    private static volatile ScheduledExecutorService delayedCloser;

    private HotSwapStore() {
    }

    /**
     * 包装初始存储，返回支持运行时热交换的 {@link KvStore} 代理
     * <p>
     * 返回对象同时实现了 {@link Swappable}，业务侧以 {@link KvStore} 类型持有即可。
     * 初始存储实现 {@link StoreWrapper} 时，代理额外实现 {@link StoreWrapper}，
     * {@code unwrap()} 经鸭子类型转发到<b>当前</b>委托——能力解析（见
     * {@link com.team4u.framework.kv.KvStores}）在热交换后仍指向新存储。
     * 初始存储实现 {@link AutoCloseable} 时，代理额外实现 {@link AutoCloseable}，
     * {@code close()} 同样经鸭子类型转发到<b>当前</b>委托——与装饰器的级联关闭语义一致；
     * 换下的旧洋葱由 Safe Swap 的交换重载负责关闭。
     * 已知边界：交换到未实现对应接口的存储后，{@code unwrap()}/{@code close()} 调用会以
     * {@link ProxyException} 失败。
     * </p>
     */
    public static KvStore wrap(KvStore initial) {
        Objects.requireNonNull(initial, "initial store");
        ProxyBuilder<KvStore> builder = ProxyBuilder.forClass(KvStore.class)
                .delegate(initial);
        if (initial instanceof StoreWrapper) {
            builder.withInterfaces(StoreWrapper.class);
        }
        if (initial instanceof AutoCloseable) {
            // close() 经鸭子类型转发到当前存储：与装饰器级联关闭语义一致
            builder.withInterfaces(AutoCloseable.class);
        }
        return builder
                .enableHotswap()
                .build();
    }

    /**
     * 原子替换底层存储，并在交换成功后静默关闭旧存储（若其实现了 {@link AutoCloseable}）
     *
     * @return 被替换下来的旧存储。<b>注意：默认已关闭，调用方不应再使用</b>
     */
    public static KvStore swapAndCloseQuietly(KvStore hotSwapProxy, KvStore newStore) {
        return swap(hotSwapProxy, newStore, 0L);
    }

    /**
     * 原子替换底层存储，旧存储若实现 {@link AutoCloseable}，则在宽限期后静默关闭。
     * <p>
     * 宽限期用于等待交换瞬间的在途调用完成，适合连接池型后端
     * （旧存储实现必须容忍 close 与调用并发）。
     * </p>
     *
     * @param gracePeriodMillis 关闭前的等待时长（毫秒），0 表示立即关闭
     * @return 被替换下来的旧存储（宽限期结束后会被关闭，调用方不应再使用）
     */
    public static KvStore swap(KvStore hotSwapProxy, KvStore newStore, long gracePeriodMillis) {
        Objects.requireNonNull(newStore, "new store");
        KvStore old = (KvStore) swappable(hotSwapProxy).hotswap(newStore);
        if (old instanceof AutoCloseable) {
            if (gracePeriodMillis > 0) {
                scheduleClose(old, gracePeriodMillis);
            } else {
                closeQuietly(old);
            }
        }
        return old;
    }

    /**
     * 原子替换底层存储，由调用方决定旧存储的关闭策略
     *
     * @return 被替换下来的旧存储（未关闭，关闭责任在调用方）
     */
    public static KvStore swap(KvStore hotSwapProxy, KvStore newStore, boolean closeOldQuietly) {
        Objects.requireNonNull(newStore, "new store");
        KvStore old = (KvStore) swappable(hotSwapProxy).hotswap(newStore);
        if (closeOldQuietly && old instanceof AutoCloseable) {
            closeQuietly(old);
        }
        return old;
    }

    private static Swappable swappable(KvStore hotSwapProxy) {
        if (!(hotSwapProxy instanceof Swappable)) {
            throw new IllegalArgumentException(
                    "Not a hot-swap proxy, wrap it first via HotSwapStore.wrap(): "
                            + hotSwapProxy.getClass().getName());
        }
        return (Swappable) hotSwapProxy;
    }

    private static void scheduleClose(KvStore store, long delayMillis) {
        ScheduledExecutorService scheduler = delayedCloser();
        try {
            scheduler.schedule(() -> closeQuietly(store), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("Failed to schedule delayed close, closing now", e);
            closeQuietly(store);
        }
    }

    private static ScheduledExecutorService delayedCloser() {
        ScheduledExecutorService scheduler = delayedCloser;
        if (scheduler == null) {
            synchronized (HotSwapStore.class) {
                scheduler = delayedCloser;
                if (scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "kv-hotswap-closer");
                        t.setDaemon(true);
                        return t;
                    });
                    delayedCloser = scheduler;
                }
            }
        }
        return scheduler;
    }

    private static void closeQuietly(KvStore store) {
        try {
            ((AutoCloseable) store).close();
        } catch (Exception e) {
            log.warn("Failed to close old kv store {}", store.getClass().getName(), e);
        }
    }
}
