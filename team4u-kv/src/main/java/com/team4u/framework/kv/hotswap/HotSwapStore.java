package com.team4u.framework.kv.hotswap;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.support.Swappable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 热交换键值存储：运行时原子替换底层存储，业务持有的引用不变
 * <p>
 * 基于 {@code ProxyBuilder} 的热交换能力（volatile delegate 替换，对所有线程立即可见）实现，
 * 任意 {@link KvStore} 实现均可包装，无需继承特定基类。适用于在线切换后端、
 * 存储迁移、故障转移等场景。
 * </p>
 * Safe Swap 约定：调用方应先构建并验证新存储可用，再执行交换；
 * 若交换下来的旧存储实现了 {@link AutoCloseable}，默认在交换成功后静默关闭
 * （尽力而为，不阻塞交换流程，关闭异常仅记录日志）。
 */
public final class HotSwapStore {

    private static final Logger log = LoggerFactory.getLogger(HotSwapStore.class);

    private HotSwapStore() {
    }

    /**
     * 包装初始存储，返回支持运行时热交换的 {@link KvStore} 代理
     * <p>
     * 返回对象同时实现了 {@link Swappable}，业务侧以 {@link KvStore} 类型持有即可。
     * </p>
     */
    public static KvStore wrap(KvStore initial) {
        return ProxyBuilder.forClass(KvStore.class)
                .delegate(initial)
                .enableHotswap()
                .build();
    }

    /**
     * 原子替换底层存储，并在交换成功后静默关闭旧存储（若其实现了 {@link AutoCloseable}）
     *
     * @return 被替换下来的旧存储
     */
    public static KvStore swapAndCloseQuietly(KvStore hotSwapProxy, KvStore newStore) {
        return swap(hotSwapProxy, newStore, true);
    }

    /**
     * 原子替换底层存储
     *
     * @param closeOldQuietly 为 {@code true} 时，旧存储实现 {@link AutoCloseable} 则静默关闭
     * @return 被替换下来的旧存储
     */
    public static KvStore swap(KvStore hotSwapProxy, KvStore newStore, boolean closeOldQuietly) {
        KvStore old = (KvStore) swappable(hotSwapProxy).hotswap(newStore);
        if (closeOldQuietly && old instanceof AutoCloseable) {
            closeQuietly(old);
        }
        return old;
    }

    private static Swappable swappable(KvStore hotSwapProxy) {
        throwIfNotSwappable(hotSwapProxy);
        return (Swappable) hotSwapProxy;
    }

    private static void throwIfNotSwappable(KvStore hotSwapProxy) {
        if (!(hotSwapProxy instanceof Swappable)) {
            throw new IllegalArgumentException(
                    "Not a hot-swap proxy, wrap it first via HotSwapStore.wrap(): "
                            + hotSwapProxy.getClass().getName());
        }
    }

    private static void closeQuietly(KvStore store) {
        try {
            ((AutoCloseable) store).close();
        } catch (Exception e) {
            log.warn("Failed to close old kv store {}", store.getClass().getName(), e);
        }
    }
}
