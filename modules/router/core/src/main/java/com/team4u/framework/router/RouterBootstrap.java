package com.team4u.framework.router;

import com.team4u.framework.router.api.exception.RouteConfigException;
import com.team4u.framework.router.api.interceptor.RouteInterceptor;
import com.team4u.framework.router.api.interceptor.RouteInterceptorRegistry;
import com.team4u.framework.router.factory.RouterFactoryRegistry;
import com.team4u.framework.router.spi.RouterFactory;

/**
 * 路由引导配置类
 * <p>
 * 该类作为路由模块的全局初始化入口，负责注册自定义路由工厂、配置拦截器以及全局参数设定。
 * </p>
 * <p>
 * 关于配置前缀的唯一硬性约束：一旦全局 {@link RoutingManager} 实例完成初始化，
 * {@link #configPrefix(String)} 将拒绝修改，以防止运行时前缀不一致引发路由丢失
 * （见 {@link RoutingManager#global()}）。工厂与拦截器的注册不做运行期锁定——
 * 注册中心本身基于写时复制，支持注册与热更新，调用方自行保证启动期装配的时序。
 * </p>
 *
 * @author jay.wu
 */
public class RouterBootstrap {

    private static final RouterBootstrap INSTANCE = new RouterBootstrap();
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
     * @throws RouteConfigException 如果全局 RoutingManager 已初始化
     */
    public RouterBootstrap configPrefix(String configPrefix) {
        if (RoutingManager.isGlobalInitialized()) {
            throw RouteConfigException.validationError(
                    "configPrefix cannot be changed after global RoutingManager initialization");
        }
        this.configPrefix = configPrefix;
        return this;
    }

    /**
     * 注册自定义路由工厂
     *
     * @param factory 路由工厂实现
     * @return 当前引导实例
     */
    public RouterBootstrap addFactory(RouterFactory factory) {
        RouterFactoryRegistry.global().register(factory);
        return this;
    }

    /**
     * 注册路由拦截器
     *
     * @param interceptor 路由拦截器
     * @return 当前引导实例
     */
    public RouterBootstrap addInterceptor(RouteInterceptor interceptor) {
        RouteInterceptorRegistry.global().register(interceptor);
        return this;
    }

    /**
     * 重置状态（仅用于测试）
     */
    public void resetForTest() {
        configPrefix = "router.";
    }
}
