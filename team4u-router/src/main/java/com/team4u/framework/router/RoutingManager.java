package com.team4u.framework.router;

import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.base.util.ServiceLoaderUtil;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.interceptor.DefaultRouteInvocation;
import com.team4u.framework.router.api.interceptor.RouteInterceptor;
import com.team4u.framework.router.api.interceptor.RouteInterceptorRegistry;
import com.team4u.framework.router.api.interceptor.RouteInvocation;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.factory.RouterFactoryRegistry;
import com.team4u.framework.router.parser.DefaultRoutePolicyParser;
import com.team4u.framework.router.spi.RoutePolicyParser;
import com.team4u.framework.router.spi.RouterFactory;

import java.util.List;

/**
 * 路由管理器
 * 统一门面，负责工厂发现与路由实例注册管理
 */
public class RoutingManager {

    private static final Log log = LogFactory.get();

    private static volatile RoutingManager GLOBAL = builder().build();

    private final RouterFactoryRegistry factoryRegistry;
    private final RoutePolicyParser configParser;
    private final String configPrefix;
    private final RouteInterceptorRegistry interceptorRegistry;

    /**
     * 配置驱动的路由器注册表
     */
    private final ConfigDrivenRegistry<Router> routerRegistry;

    private RoutingManager(RouterFactoryRegistry factoryRegistry,
                           ConfigManager configManager,
                           RoutePolicyParser configParser,
                           String configPrefix,
                           RouteInterceptorRegistry interceptorRegistry) {
        this.factoryRegistry = factoryRegistry;
        this.configParser = configParser;
        this.configPrefix = configPrefix.endsWith(".") ? configPrefix : configPrefix + ".";
        this.interceptorRegistry = interceptorRegistry;
        this.routerRegistry = new ConfigDrivenRegistry<>(
                configManager,
                this.configPrefix,
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
     * 内部工厂方法：配置字符串 -> 策略 -> 路由器实例
     */
    private Router buildRouterFromConfig(String config) {
        RoutePolicy policy = configParser.parse(config);
        if (policy == null) {
            throw new IllegalArgumentException("Unable to parse route policy from config: " + config);
        }

        return buildRouter(policy);
    }

    /**
     * 内部工厂方法：策略对象 -> 路由器实例
     */
    public Router buildRouter(RoutePolicy policy) {
        if (policy == null || StrUtil.isBlank(policy.getType())) {
            throw new IllegalArgumentException("Invalid route policy or missing type");
        }

        Router router = this.factoryRegistry.get(policy.getType())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported router type: " + policy.getType()))
                .create(policy);

        if (router == null) {
            throw new IllegalStateException("Router created from policy is null, type: " + policy.getType());
        }

        return router;
    }

    /**
     * 获取指定标识的路由器
     */
    private Router getRouter(String routerId) {
        Router router = routerRegistry.get(this.configPrefix + routerId);
        if (router == null) {
            // 当路由器未找到时，记录 DEBUG 级别的日志辅助排障
            if (log.isDebugEnabled()) {
                log.debug("Route unmatch: Router [{}] not found or failed to initialize.", routerId);
            }
        }
        return router;
    }

    /**
     * 获取路由追踪对象构造工具
     */
    private <T> RouteTrace<T> emptyTrace() {
        RouteTrace<T> trace = new RouteTrace<>();
        trace.setResult(RouteResult.unmatch());
        return trace;
    }

    /**
     * 获取拦截器注册中心
     */
    public RouteInterceptorRegistry interceptorRegistry() {
        return interceptorRegistry;
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
        return route(routerId, request, null);
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
        Router router = getRouter(routerId);
        return doRoute(routerId, router, request, targetType);
    }

    /**
     * 统一路由执行逻辑，支持拦截器链
     */
    private <T> RouteResult<T> doRoute(String routerId, Router router, Object request, Class<T> targetType) {
        List<RouteInterceptor> interceptors = interceptorRegistry.getInterceptors();
        if (interceptors == null || interceptors.isEmpty()) {
            if (router == null) {
                return RouteResult.unmatch();
            }
            return targetType != null ? router.route(request, targetType) : router.route(request);
        }

        RouteInvocation<T> invocation = new DefaultRouteInvocation<>(
                routerId, router, request, targetType, interceptors);
        return invocation.proceed();
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
        Router router = getRouter(routerId);
        return router != null ? router.trace(request) : emptyTrace();
    }

    /**
     * 获取原始配置对应的路由器
     */
    private Router getRouterByConfig(String rawConfig) {
        if (StrUtil.isBlank(rawConfig)) {
            return null;
        }
        return buildRouterFromConfig(rawConfig);
    }

    /**
     * 执行路由（针对原始配置）
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
        return routeByConfig(rawConfig, request, null);
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
        Router router = getRouterByConfig(rawConfig);
        return doRoute("raw-config", router, request, targetType);
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
        Router router = getRouterByConfig(rawConfig);
        return router != null ? router.trace(request) : emptyTrace();
    }

    /**
     * 获取路由策略对应的路由器
     */
    private Router getRouter(RoutePolicy policy) {
        if (policy == null) {
            return null;
        }
        return buildRouter(policy);
    }

    /**
     * 执行路由（针对编程式构建的 RoutePolicy）
     *
     * @param policy  路由策略对象
     * @param request 请求对象
     * @param <T>     结果类型
     * @return 路由结果
     */
    public <T> RouteResult<T> routeByPolicy(RoutePolicy policy, Object request) {
        Router router = getRouter(policy);
        return doRoute(policy.getId(), router, request, null);
    }

    /**
     * 执行路由并返回诊断轨迹（针对编程式构建的 RoutePolicy）
     *
     * @param policy  路由策略对象
     * @param request 请求对象
     * @param <T>     结果类型
     * @return 路由诊断轨迹
     */
    public <T> RouteTrace<T> traceByPolicy(RoutePolicy policy, Object request) {
        Router router = getRouter(policy);
        return router != null ? router.trace(request) : emptyTrace();
    }

    /**
     * 路由管理器构造器
     */
    public static class Builder {

        private RouterFactoryRegistry factoryRegistry;
        private ConfigManager configManager;
        private RoutePolicyParser configParser;
        private String configPrefix = "router.";
        private RouteInterceptorRegistry interceptorRegistry;
        private boolean useGlobalInterceptors = true;

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
         * 自定义配置前缀，默认值为 "router."
         */
        public Builder configPrefix(String configPrefix) {
            if (StrUtil.isNotBlank(configPrefix)) {
                this.configPrefix = configPrefix;
            }
            return this;
        }

        /**
         * 设置拦截器注册中心
         */
        public Builder interceptorRegistry(RouteInterceptorRegistry interceptorRegistry) {
            this.interceptorRegistry = interceptorRegistry;
            this.useGlobalInterceptors = false; // 如果手动指定了注册中心，则不使用全局的
            return this;
        }

        /**
         * 是否使用全局拦截器（默认为 true）
         */
        public Builder useGlobalInterceptors(boolean useGlobalInterceptors) {
            this.useGlobalInterceptors = useGlobalInterceptors;
            return this;
        }

        /**
         * 添加拦截器到当前注册中心
         */
        public Builder addInterceptor(RouteInterceptor interceptor) {
            if (interceptor != null) {
                if (this.interceptorRegistry == null) {
                    this.interceptorRegistry = new RouteInterceptorRegistry();
                    this.useGlobalInterceptors = false;
                }
                this.interceptorRegistry.register(interceptor);
            }
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

            // 拦截器处理
            RouteInterceptorRegistry finalInterceptorRegistry = this.interceptorRegistry;
            if (finalInterceptorRegistry == null) {
                finalInterceptorRegistry = useGlobalInterceptors ? RouteInterceptorRegistry.global()
                        : new RouteInterceptorRegistry();
            }

            // 如果是新创建的对象或者是全局对象且尚未初始化，可以考虑在这里 autoScan
            // 但通常全局对象应该由用户手动控制或在特定生命周期初始化
            // 这里的逻辑是：如果是 Builder 模式默认创建的，且没有手动指定过 registry，则尝试扫描
            if (this.interceptorRegistry == null) {
                finalInterceptorRegistry.autoScan();
            }

            return new RoutingManager(finalRegistry, cm, finalParser, configPrefix, finalInterceptorRegistry);
        }
    }
}