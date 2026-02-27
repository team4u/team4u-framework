package com.team4u.framework.router;

import com.team4u.framework.router.factory.RouterFactoryRegistry;
import com.team4u.framework.router.spi.RouterFactory;

/**
 * 路由管理模块全局引导配置类
 * <p>
 * 提供统一的入口进行全局路由工厂的注册，避免注册逻辑散落在各处。
 * 支持锁定机制，确保应用启动后的稳定性。
 */
public class RouterBootstrap {

    private static final RouterBootstrap INSTANCE = new RouterBootstrap();

    private volatile boolean locked = false;

    private RouterBootstrap() {
    }

    /**
     * 获取全局引导实例
     */
    public static RouterBootstrap global() {
        return INSTANCE;
    }

    /**
     * 注册全局自定义路由工厂
     *
     * @param factory 路由工厂实现
     */
    public synchronized RouterBootstrap addFactory(RouterFactory factory) {
        checkLocked();
        RouterFactoryRegistry.global().register(factory);
        return this;
    }

    /**
     * 锁定全局注册表
     * <p>
     * 调用后将禁止任何新的注册操作，建议在应用启动完成（如 Spring 启动成功）后调用。
     */
    public synchronized void lock() {
        this.locked = true;
    }

    /**
     * 检查是否已锁定
     */
    private void checkLocked() {
        if (locked) {
            throw new IllegalStateException("Router global registry is locked, no more registrations allowed.");
        }
    }
}
