package com.team4u.framework.config.core.proxy;

/**
 * 代理实例快照感知增强接口
 * <p>
 * 该接口由框架自动织入所有配置代理实例中。
 * 通过此接口，用户可以将动态更新的代理对象切换为固定版本的快照代理，以确保业务逻辑执行期间配置的一致性。
 * </p>
 *
 * @param <T> 被代理的原始接口类型
 */
public interface SnapshotAware<T> {

    /**
     * 将配置代理实例锚定到当前快照版本
     * <p>
     * 静态辅助方法，封装了强制转换逻辑。
     * 最佳实践：在业务处理流程的入口处调用此方法，后续逻辑通过返回的固定版本对象执行。
     * </p>
     *
     * @param proxy 已实现 SnapshotAware 的配置代理实例
     * @param <T>   目标接口类型
     * @return 锚定在当前快照版本的新代理实例
     * @throws IllegalArgumentException 若传入对象未实现 SnapshotAware 接口
     */
    @SuppressWarnings("unchecked")
    static <T> T pin(T proxy) {
        if (proxy instanceof SnapshotAware) {
            return ((SnapshotAware<T>) proxy).pin();
        }
        throw new IllegalArgumentException("对象未实现 SnapshotAware 接口，无法执行快照锚定");
    }

    /**
     * 执行快照锚定
     * <p>
     * 生成并返回一个新的代理实例，该实例内部持有调用时刻的固定配置快照副本。
     * </p>
     *
     * @return 锚定在固定快照的新代理对象
     */
    T pin();
}
