package com.team4u.framework.router;

import com.team4u.framework.router.factory.RouterFactoryRegistry;
import com.team4u.framework.router.spi.RouterFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 路由管理模块全局引导配置类
 * <p>
 * 提供统一的入口进行全局路由工厂的注册，避免注册逻辑散落在各处。
 * 支持锁定机制，确保应用启动后的稳定性。
 * </p>
 *
 * @author jay.wu
 */
public class RouterBootstrap {

    private static final RouterBootstrap INSTANCE = new RouterBootstrap();

    /**
     * 全局配置前缀，使用 volatile 保证可见性
     */
    private volatile String configPrefix = "router.";

    /**
     * 锁定标志，使用 AtomicBoolean 保证原子性操作
     */
    private final AtomicBoolean locked = new AtomicBoolean(false);

    private RouterBootstrap() {
    }

    /**
     * 获取全局引导实例
     *
     * @return 全局实例
     */
    public static RouterBootstrap global() {
        return INSTANCE;
    }

    /**
     * 获取全局配置前缀（默认为 router.）
     *
     * @return 配置前缀
     */
    public String getConfigPrefix() {
        return configPrefix;
    }

    /**
     * 设置全局配置前缀
     * <p>
     * 设置后将自动重置 RoutingManager 的全局实例，以应用新的前缀。
     * </p>
     *
     * @param configPrefix 配置前缀
     * @return 当前实例（支持链式调用）
     * @throws IllegalStateException 如果已锁定
     */
    public RouterBootstrap configPrefix(String configPrefix) {
        checkLocked();
        this.configPrefix = configPrefix;
        // 设置前缀后，由于 RoutingManager.global() 是在加载时就初始化的，
        // 这里需要强制刷新全局实例以使前缀生效。
        RoutingManager.setGlobal(RoutingManager.builder().build());
        return this;
    }

    /**
     * 注册全局自定义路由工厂
     *
     * @param factory 路由工厂实现
     * @return 当前实例（支持链式调用）
     * @throws IllegalStateException 如果已锁定
     */
    public RouterBootstrap addFactory(RouterFactory factory) {
        checkLocked();
        RouterFactoryRegistry.global().register(factory);
        return this;
    }

    /**
     * 锁定全局注册表
     * <p>
     * 调用后将禁止任何新的注册操作，建议在应用启动完成（如 Spring 启动成功）后调用。
     * 此方法是幂等的，多次调用不会抛出异常。
     * </p>
     */
    public void lock() {
        locked.set(true);
    }

    /**
     * 检查是否已锁定
     *
     * @return 如果已锁定返回 true
     */
    public boolean isLocked() {
        return locked.get();
    }

    /**
     * 解锁全局注册表
     * <p>
     * 此方法主要用于测试场景，生产环境不建议使用。
     * </p>
     */
    public void unlock() {
        locked.set(false);
    }

    /**
     * 检查锁定状态，如果已锁定则抛出异常
     *
     * @throws IllegalStateException 如果已锁定
     */
    private void checkLocked() {
        if (locked.get()) {
            throw new IllegalStateException("Router global registry is locked, no more registrations allowed.");
        }
    }
}
