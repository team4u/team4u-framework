package com.team4u.framework.kv;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * 存储解析工具：沿装饰器链查找底层真实存储或能力实现
 * <p>
 * 装饰器实现了 {@link StoreWrapper} 后，本工具即可穿透任意深度的装饰洋葱。
 * 锁管理器、清理器、轮询订阅等需要能力协商的组件在构造期自动调用本工具解析，
 * 调用方传入装饰过的存储（如 {@code ObservedStore(TieredStore(redisStore))}）无需任何处理。
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
public final class KvStores {

    private KvStores() {
    }

    /**
     * 静默关闭存储（若实现 {@link AutoCloseable}），关闭异常仅记录日志。
     * <p>
     * 所有装饰器的 close 级联语义统一走此入口：关闭最外层装饰器即释放整棵洋葱。
     * </p>
     */
    public static void closeQuietly(KvStore store) {
        if (!(store instanceof AutoCloseable)) {
            return;
        }
        try {
            ((AutoCloseable) store).close();
        } catch (Exception e) {
            log.warn("Failed to close kv store {}", store.getClass().getName(), e);
        }
    }

    /**
     * 解包全部装饰层，返回最内层的真实存储
     */
    public static KvStore innermost(KvStore store) {
        KvStore current = Objects.requireNonNull(store, "store");
        while (current instanceof StoreWrapper) {
            current = ((StoreWrapper) current).unwrap();
        }
        return current;
    }

    /**
     * 沿装饰器链查找实现指定能力接口的存储
     *
     * @param capabilityType 能力接口（如 {@code CasCapable.class}、{@code ScanCapable.class}）
     * @return 链上第一个实现该能力的存储；整条链均不支持返回 {@code null}
     */
    public static <T> T capabilityOf(KvStore store, Class<T> capabilityType) {
        Objects.requireNonNull(capabilityType, "capabilityType");
        KvStore current = Objects.requireNonNull(store, "store");
        while (true) {
            if (capabilityType.isInstance(current)) {
                return capabilityType.cast(current);
            }
            if (!(current instanceof StoreWrapper)) {
                return null;
            }
            current = ((StoreWrapper) current).unwrap();
        }
    }
}
