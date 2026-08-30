package com.team4u.framework.kv.hotswap;

import com.team4u.framework.kv.HotSwap;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.StoreWrapper;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 热交换键值存储：运行时原子替换底层存储，业务持有的引用不变
 * <p>
 * 任意 {@link KvStore} 实现均可包装，无需继承特定基类。适用于在线切换后端、
 * 存储迁移、故障转移等场景。
 * </p>
 * <p>
 * 返回代理的能力接口在创建时固定：总是实现 {@link KvStore} 与 {@link HotSwap}；
 * 初始存储实现 {@link StoreWrapper} 时额外实现 {@link StoreWrapper}，实现
 * {@link AutoCloseable} 时额外实现 {@link AutoCloseable}。后续交换不会扩大或缩小
 * 代理的接口集合；若新存储缺少已公开能力，对应调用会明确失败。
 * </p>
 * <p>
 * Safe Swap 约定：调用方应先构建并验证新存储可用，再执行交换；
 * HotSwap#hotswap(Object) 仅原子替换并返回旧存储，不负责关闭。使用静态
 * {@code swap} 重载可保留旧的立即关闭或宽限期关闭语义。
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
     * 业务侧以 {@link KvStore} 类型持有即可。代理接口集合在创建时固定：
     * {@link StoreWrapper} 与 {@link AutoCloseable} 仅按初始存储能力公开，
     * 交换不会动态增加能力，也不会静默移除已公开能力。缺少能力的交换目标会让
     * 对应调用失败，而不是把代理降级成另一个运行时类型。
     * </p>
     */
    public static KvStore wrap(KvStore initial) {
        Objects.requireNonNull(initial, "initial store");
        AtomicReference<KvStore> current = new AtomicReference<>(initial);

        Class<?>[] interfaces;
        if (initial instanceof StoreWrapper && initial instanceof AutoCloseable) {
            interfaces = new Class<?>[] {KvStore.class, HotSwap.class,
                    StoreWrapper.class, AutoCloseable.class};
        } else if (initial instanceof StoreWrapper) {
            interfaces = new Class<?>[] {KvStore.class, HotSwap.class, StoreWrapper.class};
        } else if (initial instanceof AutoCloseable) {
            interfaces = new Class<?>[] {KvStore.class, HotSwap.class, AutoCloseable.class};
        } else {
            interfaces = new Class<?>[] {KvStore.class, HotSwap.class};
        }

        return (KvStore) Proxy.newProxyInstance(
                HotSwapStore.class.getClassLoader(),
                interfaces,
                new Handler(current));
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
        KvStore old = (KvStore) hotSwap(hotSwapProxy).hotswap(newStore);
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
        KvStore old = (KvStore) hotSwap(hotSwapProxy).hotswap(newStore);
        if (closeOldQuietly && old instanceof AutoCloseable) {
            closeQuietly(old);
        }
        return old;
    }

    private static HotSwap hotSwap(KvStore hotSwapProxy) {
        if (!(hotSwapProxy instanceof HotSwap)) {
            throw new IllegalArgumentException(
                    "Not a hot-swap proxy, wrap it first via HotSwapStore.wrap(): "
                            + hotSwapProxy.getClass().getName());
        }
        return (HotSwap) hotSwapProxy;
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

    private static final class Handler implements InvocationHandler {

        private final AtomicReference<KvStore> current;

        private Handler(AtomicReference<KvStore> current) {
            this.current = current;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if (method.getDeclaringClass() == HotSwap.class && methodName.equals("hotswap")) {
                Object newDelegate = args == null || args.length == 0 ? null : args[0];
                if (!(newDelegate instanceof KvStore)) {
                    throw new IllegalArgumentException(
                            "hotswap requires a non-null com.team4u.framework.kv.KvStore delegate");
                }
                return current.getAndSet((KvStore) newDelegate);
            }
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, methodName, args);
            }

            KvStore delegate = current.get();
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IllegalStateException(
                        "Current hot-swap delegate no longer supports method " + method
                                + ": " + delegate.getClass().getName(), e);
            }
        }

        private Object objectMethod(Object proxy, String methodName, Object[] args) {
            if (methodName.equals("equals")) {
                return proxy == args[0];
            }
            if (methodName.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            return "HotSwapStore proxy for " + current.get().getClass().getName();
        }
    }
}
