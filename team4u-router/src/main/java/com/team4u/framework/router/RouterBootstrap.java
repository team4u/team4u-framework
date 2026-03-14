package com.team4u.framework.router;

import com.team4u.framework.router.api.exception.RouteConfigException;
import com.team4u.framework.router.api.interceptor.RouteInterceptor;
import com.team4u.framework.router.api.interceptor.RouteInterceptorRegistry;
import com.team4u.framework.router.factory.RouterFactoryRegistry;
import com.team4u.framework.router.spi.RouterFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 路由引导配置类
 * <p>
 * 该类作为路由模块的全局初始化入口，负责注册自定义路由工厂、配置拦截器以及全局参数设定。
 * 为了确保系统在运行期间的确定性，支持锁定（Locked）和冻结（Frozen）机制：
 * <ul>
 *   <li><b>锁定</b>：锁定后禁止注册新的工厂或拦截器，通常在应用启动完成（如 Spring Context 加载完毕）后执行。</li>
 *   <li><b>冻结</b>：一旦全局 RoutingManager 实例初始化，配置前缀等全局参数将被冻结，禁止修改，以防止运行时行为不一致。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public class RouterBootstrap {

    private static final RouterBootstrap INSTANCE = new RouterBootstrap();
    /**
     * 锁定标志，锁定后禁止注册新工厂或拦截器
     */
    private final AtomicBoolean locked = new AtomicBoolean(false);
    /**
     * 配置冻结标志，冻结后禁止修改配置前缀
     */
    private final AtomicBoolean frozen = new AtomicBoolean(false);
    /**
     * 配置键前缀，默认为 "router."
     */
    private volatile String configPrefix = "router.";

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
     * 获取全局配置键前缀
     *
     * @return 配置前缀
     */
    public String getConfigPrefix() {
        return configPrefix;
    }

    /**
     * 设置全局配置键的前缀
     * <p>
     * 该设置仅在全局 RoutingManager 实例初始化之前有效。
     * </p>
     *
     * @param configPrefix 配置前缀
     * @return 当前引导实例
     * @throws IllegalStateException 如果已锁定或已由于初始化而被冻结
     */
    public RouterBootstrap configPrefix(String configPrefix) {
        checkMutable();
        this.configPrefix = configPrefix;
        return this;
    }

    /**
     * 注册自定义路由工厂
     *
     * @param factory 路由工厂实现
     * @return 当前引导实例
     * @throws IllegalStateException 如果已锁定
     */
    public RouterBootstrap addFactory(RouterFactory factory) {
        checkMutable();
        RouterFactoryRegistry.global().register(factory);
        return this;
    }

    /**
     * 注册路由拦截器
     *
     * @param interceptor 路由拦截器
     * @return 当前引导实例
     * @throws IllegalStateException 如果已锁定
     */
    public RouterBootstrap addInterceptor(RouteInterceptor interceptor) {
        checkMutable();
        RouteInterceptorRegistry.global().register(interceptor);
        return this;
    }

    /**
     * 锁定全局配置。
     * <p>
     * 执行锁定后，所有 addFactory 和 addInterceptor 操作将抛出异常。
     * 建议在生产环境的应用启动钩子中调用。
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
     * 检查是否已冻结
     *
     * @return 如果已冻结返回 true
     */
    public boolean isFrozen() {
        return frozen.get();
    }

    /**
     * 解锁全局配置
     * <p>
     * 注意：此方法主要用于测试场景。
     * </p>
     */
    public void unlock() {
        locked.set(false);
    }

    /**
     * 冻结配置
     * <p>
     * 一旦全局 RoutingManager 实例化，将自动调用此方法。
     * </p>
     */
    void freezeConfig() {
        frozen.set(true);
    }

    /**
     * 重置状态（仅用于测试）
     */
    public void resetForTest() {
        locked.set(false);
        frozen.set(false);
        configPrefix = "router.";
    }

    /**
     * 检查状态，如果不可变则抛出异常
     */
    private void checkMutable() {
        if (locked.get()) {
            throw RouteConfigException.registryLocked();
        }
        if (frozen.get() || RoutingManager.isGlobalInitialized()) {
            throw RouteConfigException.validationError(
                    "configPrefix cannot be changed after global RoutingManager initialization");
        }
    }
}

