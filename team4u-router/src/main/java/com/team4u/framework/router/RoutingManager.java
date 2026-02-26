package com.team4u.framework.router;

import cn.hutool.json.JSONUtil;
import com.team4u.framework.base.instance.DynamicInstanceProvider;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RouteResult;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.factory.ExpressionRouterFactory;
import com.team4u.framework.router.factory.RouterFactory;
import com.team4u.framework.router.factory.RouterFactoryRegistry;

/**
 * 路由管理器
 * 统一门面，负责工厂发现与实例缓存
 */
public class RoutingManager {

    private static volatile RoutingManager GLOBAL = builder().build();

    private final RouterFactoryRegistry factoryRegistry;
    private final ConfigManager configManager;
    /**
     * 高性能实例提供者
     * String (JSON) -> RoutePolicy -> Router
     */
    private final DynamicInstanceProvider<String, RoutePolicy, Router> provider;

    private RoutingManager(RouterFactoryRegistry factoryRegistry, ConfigManager configManager) {
        this.factoryRegistry = factoryRegistry;
        this.configManager = configManager;
        this.provider = DynamicInstanceProvider.createStringLru(
                1000,
                json -> JSONUtil.toBean(json, RoutePolicy.class),
                policy -> this.factoryRegistry.get(policy.getType())
                        .orElseThrow(() -> new IllegalArgumentException("Unsupported router type: " + policy.getType()))
                        .create(policy)
        );
    }

    /**
     * 获取全局实例
     */
    public static RoutingManager global() {
        return GLOBAL;
    }

    /**
     * 重置或替换全局实例
     */
    public static void setGlobal(RoutingManager routingManager) {
        GLOBAL = routingManager;
    }

    /**
     * 创建路由管理器构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 执行路由（通过路由唯一标识）
     *
     * @param routerId 路由唯一标识（用于从 ConfigManager 获取 JSON 配置）
     * @param request  路由请求对象
     * @param <T>      结果类型
     * @return 路由结果
     */
    public <T> RouteResult<T> route(String routerId, Object request) {
        String rawJsonConfig = configManager.getString(routerId).orElse(null);
        return routeByConfig(rawJsonConfig, request);
    }

    /**
     * 执行路由（针对原始 JSON 配置）
     * <p>
     * 方便单元测试或直接透传配置场景。
     * </p>
     *
     * @param rawJsonConfig JSON 配置字符串
     * @param request       路由请求对象
     * @param <T>           结果类型
     * @return 路由结果
     */
    public <T> RouteResult<T> routeByConfig(String rawJsonConfig, Object request) {
        if (rawJsonConfig == null || rawJsonConfig.trim().isEmpty()) {
            return RouteResult.unmatch();
        }

        // 获取或创建缓存的 Router 实例
        Router router = provider.get(rawJsonConfig);
        if (router == null) {
            return RouteResult.unmatch();
        }

        return router.route(request);
    }

    /**
     * 路由管理器构造器
     */
    public static class Builder {

        private RouterFactoryRegistry factoryRegistry;
        private ConfigManager configManager;

        public Builder() {
        }

        /**
         * 自定义工厂注册表
         */
        public Builder factoryRegistry(RouterFactoryRegistry factoryRegistry) {
            this.factoryRegistry = factoryRegistry;
            return this;
        }

        /**
         * 手动添加单个路由工厂
         */
        public Builder addFactory(RouterFactory factory) {
            if (factory != null) {
                if (this.factoryRegistry == null) {
                    this.factoryRegistry = new RouterFactoryRegistry();
                }
                this.factoryRegistry.register(factory);
            }
            return this;
        }

        /**
         * 设置表达式路由器的自定义匹配规则引擎
         *
         * @param criteria 匹配规则引擎
         * @return 当前 Builder 实例
         */
        public Builder expressionCriteria(Criteria criteria) {
            return addFactory(new ExpressionRouterFactory(criteria));
        }

        /**
         * 指定配置管理器，如果未指定则默认使用 {@link ConfigManager#global()}
         */
        public Builder configManager(ConfigManager configManager) {
            this.configManager = configManager;
            return this;
        }

        /**
         * 构建路由管理器实例
         */
        public RoutingManager build() {
            RouterFactoryRegistry registry = this.factoryRegistry;
            if (registry == null) {
                registry = RouterFactoryRegistry.global();
            }

            // 先自动扫描和加载，后注册手动添加的工厂，以确保手动注册具有更高优先级（覆盖自动发现的同名工厂）
            RouterFactoryRegistry finalRegistry = new RouterFactoryRegistry();
            PolicyScanner.registerFromServiceLoader(finalRegistry);
            PolicyScanner.scanAndRegister(finalRegistry);
            finalRegistry.addAll(registry);

            ConfigManager cm = this.configManager;
            if (cm == null) {
                cm = ConfigManager.global();
            }
            return new RoutingManager(finalRegistry, cm);
        }
    }
}