package com.team4u.framework.config.core.proxy;

import com.team4u.framework.config.core.ConfigManager;

/**
 * 动态代理感知接口，用于快照切换。
 * <p>
 * 所有的配置接口在被 {@link ConfigManager#createProxy} 代理后，
 * 均可强转为此接口，以实现快照锚定，避免一致性撕裂。
 *
 * @param <T> 被代理的原始接口类型
 */
public interface SnapshotAware<T> {

    /**
     * 静态工具方法：将任意配置代理对象锚定到当前快照
     * <p>
     * 该方法封装了强制转换逻辑，提供更友好的 API。
     * 若传入对象未实现 {@link SnapshotAware} 接口，将抛出异常。
     * </p>
     *
     * @param proxy 实现过 SnapshotAware 的配置代理对象
     * @param <T>   目标配置接口类型
     * @return 锚定后的固定快照代理对象
     * @see #pin()
     * @apiNote 最佳实践：遵循“一次锚定，多次复用”原则。建议在 Service 或请求入口处执行一次 pin()，
     *          然后在后续业务逻辑中复用返回的结果，以确保高性能和逻辑一致性。
     */
    @SuppressWarnings("unchecked")
    static <T> T pin(T proxy) {
        if (proxy instanceof SnapshotAware) {
            return ((SnapshotAware<T>) proxy).pin();
        }
        throw new IllegalArgumentException("对象未实现 SnapshotAware 接口，无法进行快照锚定");
    }

    /**
     * “钉住” 当前配置状态
     * <p>
     * 获取一个新的固定快照代理。该代理内含绑定的老版本 ConfigSnapshot，
     * 不会随全局更新而变，以防破坏一致性或产生“撕裂读取”。
     *
     * @return 固定快照代理对象
     */
    T pin();
}
