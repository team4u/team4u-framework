package com.team4u.framework.router;

import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.base.util.ServiceLoaderUtil;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.factory.RouterFactoryRegistry;
import com.team4u.framework.router.parser.DefaultRoutePolicyParser;
import com.team4u.framework.router.spi.RoutePolicyParser;
import com.team4u.framework.router.spi.RouterFactory;

/**
 * 路由管理器
 * 统一门面，负责工厂发现与路由实例注册管理
 */
public class RoutingManager {

    private static final Log log = LogFactory.get();

    private static volatile RoutingManager GLOBAL = builder().build();

    private final RouterFactoryRegistry factoryRegistry;
    private final ConfigManager configManager;
    private final RoutePolicyParser configParser;

    /**
     * 配置驱动的路由器注册表
     */
    private final ConfigDrivenRegistry<Router> routerRegistry;

    private RoutingManager(RouterFactoryRegistry factoryRegistry,
            ConfigManager configManager,
            RoutePolicyParser configParser) {
        this.factoryRegistry = factoryRegistry;
        this.configManager = configManager;
        this.configParser = configParser;
        this.routerRegistry = new ConfigDrivenRegistry<>(
                this.configManager,
                "router.",
                this::buildRouterFromConfig);
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
     * 内部工厂方法：Config String -> Policy -> Router
     */
    private Router buildRouterFromConfig(String config) {
        RoutePolicy policy = configParser.parse(config);
        if (policy == null) {
            return null;
        }
        return this.factoryRegistry.get(policy.getType())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported router type: " + policy.getType()))
                .create(policy);
    }

    /**
     * 执行路由（通过路由唯一标识）
     *
     * @param routerId 路由唯一标识（对应的配置键为 router.{routerId}）
     * @param request  路由请求对象
     * @param <T>      结果类型
     * @return 路由结果
     */
    public <T> RouteResult<T> route(String routerId, Object request) {
        Router router = routerRegistry.get("router." + routerId);
        if (router == null) {
            // 当路由器未找到时，记录 DEBUG 级别的日志辅助排障
            if (log.isDebugEnabled()) {
                log.debug("Route unmatch: Router [{}] not found or failed to initialize.", routerId);
            }
            return RouteResult.unmatch();
        }
        return router.route(request);
    }

    /**
     * 执行路由并转换结果类型（通过路由唯一标识）
     *
     * @param routerId   路由唯一标识
     * @param request    路由请求对象
     * @param targetType 期望转换的目标类型
     * @param <T>        结果类型
     * @return 路由结果
     */
    public <T> RouteResult<T> route(String routerId, Object request, Class<T> targetType) {
        Router router = routerRegistry.get("router." + routerId);
        if (router == null) {
            // 当路由器未找到时，记录 DEBUG 级别的日志辅助排障
            if (log.isDebugEnabled()) {
                log.debug("Route unmatch: Router [{}] not found or failed to initialize.", routerId);
            }
            return RouteResult.unmatch();
        }
        return router.route(request, targetType);
    }

    /**
     * 执行路由并返回诊断轨迹（通过路由唯一标识）
     *
     * @param routerId 路由唯一标识
     * @param request  路由请求对象
     * @param <T>      结果类型
     * @return 路由诊断轨迹
     */
    public <T> RouteTrace<T> trace(String routerId, Object request) {
        Router router = routerRegistry.get("router." + routerId);
        if (router == null) {
            RouteTrace<T> trace = new RouteTrace<>();
            trace.setResult(RouteResult.unmatch());
            return trace;
        }
        return router.trace(request);
    }

    /**
     * 执行路由（针对原始 JSON 配置）
     * <p>
     * 方便单元测试或直接透传配置场景。
     * </p>
     *
     * @param rawConfig 配置字符串
     * @param request   路由请求对象
     * @param <T>       结果类型
     * @return 路由结果
     */
    public <T> RouteResult<T> routeByConfig(String rawConfig, Object request) {
        if (StrUtil.isBlank(rawConfig)) {
            return RouteResult.unmatch();
        }

        Router router = buildRouterFromConfig(rawConfig);
        return router.route(request);
    }

    /**
     * 执行路由并转换结果类型（针对原始配置）
     *
     * @param rawConfig  配置字符串
     * @param request    路由请求对象
     * @param targetType 期望转换的目标类型
     * @param <T>        结果类型
     * @return 路由结果
     */
    public <T> RouteResult<T> routeByConfig(String rawConfig, Object request, Class<T> targetType) {
        if (StrUtil.isBlank(rawConfig)) {
            return RouteResult.unmatch();
        }

        Router router = buildRouterFromConfig(rawConfig);
        return router.route(request, targetType);
    }

    /**
     * 执行路由并返回诊断轨迹（针对原始配置）
     *
     * @param rawConfig 配置字符串
     * @param request   路由请求对象
     * @param <T>       结果类型
     * @return 路由诊断轨迹
     */
    public <T> RouteTrace<T> traceByConfig(String rawConfig, Object request) {
        if (StrUtil.isBlank(rawConfig)) {
            RouteTrace<T> trace = new RouteTrace<>();
            trace.setResult(RouteResult.unmatch());
            return trace;
        }

        Router router = buildRouterFromConfig(rawConfig);
        return router.trace(request);
    }

    /**
     * 路由管理器构造器
     */
    public static class Builder {

        private RouterFactoryRegistry factoryRegistry;
        private ConfigManager configManager;
        private RoutePolicyParser configParser;

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
         * 指定配置管理器，如果未指定则默认使用 {@link ConfigManager#global()}
         */
        public Builder configManager(ConfigManager configManager) {
            this.configManager = configManager;
            return this;
        }

        /**
         * 自定义路由策略解析器
         */
        public Builder configParser(RoutePolicyParser configParser) {
            this.configParser = configParser;
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
            PolicyScanner.scanAndRegister(finalRegistry, "com.team4u.framework.router");
            finalRegistry.addAll(registry);

            ConfigManager cm = this.configManager;
            if (cm == null) {
                cm = ConfigManager.global();
            }

            // 1. 优先使用 Builder 手动传入的 parser
            RoutePolicyParser finalParser = this.configParser;

            // 2. 如果未指定，尝试通过 SPI 机制发现用户提供的实现
            if (finalParser == null) {
                finalParser = ServiceLoaderUtil.loadFirstAvailable(RoutePolicyParser.class);
            }

            // 3. 如果 SPI 也没有，兜底使用默认实现 (Hutool)
            if (finalParser == null) {
                finalParser = new DefaultRoutePolicyParser();
            }

            return new RoutingManager(finalRegistry, cm, finalParser);
        }
    }
}