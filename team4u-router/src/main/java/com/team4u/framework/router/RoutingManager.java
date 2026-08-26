package com.team4u.framework.router;

import com.team4u.framework.base.util.ServiceLoaderUtil;
import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.exception.RouteConfigException;
import com.team4u.framework.router.api.interceptor.*;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.api.trace.RouteTraceEvent;
import com.team4u.framework.router.factory.CompositeRouterFactory;
import com.team4u.framework.router.factory.RouterFactoryRegistry;
import com.team4u.framework.router.parser.DefaultRoutePolicyParser;
import com.team4u.framework.router.spi.RoutePolicyParser;
import com.team4u.framework.router.spi.RouterFactory;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 路由管理器（Routing Facade）
 * <p>
 * 该类是路由模块的核心入口，采用门面模式对外统一提供路由执行、诊断（Trace）以及配置解析功能。
 * 它管理着：
 * <ul>
 *   <li>{@link RouterFactoryRegistry}：按类型（如 weight, map, expression 等）管理路由创建工厂。</li>
 *   <li>{@link RouteInterceptorRegistry}：管理路由执行前后的拦截插件。</li>
 *   <li>{@link ConfigDrivenRegistry}：基于配置中心动态感知路由配置变更，并实时更新对应的 Router 实例。</li>
 * </ul>
 * 这种设计允许开发者通过简单的 ID 引用执行逻辑，即便底层的路由策略由 DBA 或运营人员在配置中心实时修改。
 * </p>
 */
@Getter
public class RoutingManager {

    private static final Logger log = LoggerFactory.getLogger(RoutingManager.class);

    private static volatile RoutingManager GLOBAL;

    /**
     * 路由工厂注册器，可供外部手工补充注册自定义工厂
     */
    private final RouterFactoryRegistry factoryRegistry;
    /**
     * 拦截器注册中心
     */
    private final RouteInterceptorRegistry interceptorRegistry;

    private final RoutePolicyParser configParser;
    /**
     * 配置键前缀，默认为 "router."
     */
    private volatile String configPrefix = "router.";
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
        // 构建配置驱动的动态注册表，将配置中心的 JSON/YAML 自动映射为 Router 实例
        this.routerRegistry = new ConfigDrivenRegistry<>(
                configManager,
                this.configPrefix.endsWith("*") ? this.configPrefix : this.configPrefix + "*",
                this::buildRouterFromConfig);
    }

    /**
     * 获取全局共享的 RoutingManager 实例。
     * <p>
     * 首次调用时将触发全局初始化，并锁定 {@link RouterBootstrap} 中的相关配置。
     * </p>
     *
     * @return 全局实例
     */
    public static RoutingManager global() {
        RoutingManager current = GLOBAL;
        if (current != null) {
            return current;
        }

        synchronized (RoutingManager.class) {
            if (GLOBAL == null) {
                RouterBootstrap.global().freezeConfig();
                GLOBAL = builder().build();
            }
            return GLOBAL;
        }
    }

    /**
     * 重置或替换全局实例（主要用于测试）
     */
    public static void setGlobal(RoutingManager routingManager) {
        GLOBAL = routingManager;
        if (routingManager != null) {
            RouterBootstrap.global().freezeConfig();
        }
    }

    /**
     * 判断全局实例是否已初始化
     */
    static boolean isGlobalInitialized() {
        return GLOBAL != null;
    }

    /**
     * 重置全局实例（仅用于测试）
     */
    public static void resetGlobalForTest() {
        GLOBAL = null;
    }

    /**
     * 创建路由管理器构造器，用于构建自定义上下文的路由管理器。
     *
     * @return 构造器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 内部工厂方法：配置字符串 -> 策略 -> 路由器实例
     */
    private Router buildRouterFromConfig(String config) {
        RoutePolicy policy;
        try {
            policy = configParser.parse(config);
        } catch (Exception e) {
            throw RouteConfigException.parseError("Failed to parse route policy from config: " + config, e);
        }
        if (policy == null) {
            throw RouteConfigException.parseError("Unable to parse route policy from config: " + config);
        }

        return buildRouter(policy);
    }

    /**
     * 基于路由策略对象创建路由器实例。
     * <p>
     * 该方法会利用已注册的 {@link RouterFactory} 按类型生成具体的实现类（如权重路由、规则路由等）。
     * </p>
     *
     * @param policy 路由策略，包含类型及其特定配置参数
     * @return 具体的 Router 实现
     * @throws RouteConfigException 如果类型不支持或创建失败
     */
    public Router buildRouter(RoutePolicy policy) {
        if (policy == null || StringUtil.isBlank(policy.getType())) {
            throw RouteConfigException.validationError("Invalid route policy or missing type");
        }

        String routerType = policy.getType();
        Router router = this.factoryRegistry.get(routerType)
                .orElseThrow(() -> RouteConfigException.unsupportedType(routerType))
                .create(policy);

        if (router == null) {
            throw RouteConfigException.validationError(
                    policy.getId(),
                    "Router created from policy is null, type: " + routerType);
        }

        return router;
    }

    /**
     * 获取指定标识的路由器
     */
    private Router getRouter(String routerId) {
        Router router = routerRegistry.get(routerId);
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
     * 执行路由（通过路由唯一标识）
     *
     * @param routerId 路由唯一标识（对应的配置键为 router.{routerId}）
     * @param request  路由请求对象
     * @param <T>      结果类型
     * @return 路由结果
     */
    public <T> RouteResult<T> route(String routerId, Object request) {
        Router router = getRouter(routerId);
        return doRoute(routerId, router, request, null);
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
        return route(routerId, request, (Type) targetType);
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
    public <T> RouteResult<T> route(String routerId, Object request, Type targetType) {
        Router router = getRouter(routerId);
        return doRoute(routerId, router, request, targetType);
    }

    /**
     * 统一路由执行逻辑，支持拦截器链
     */
    public <T> RouteResult<T> route(String routerId, Object request, TypeReference<T> typeReference) {
        return route(routerId, request, typeReference != null ? typeReference.getType() : null);
    }

    /**
     * 核心路由分发逻辑。
     * <p>
     * 支持多重拦截（Interception），执行过程遵循以下流水线：
     * Interceptor1 -> Interceptor2 -> ... -> Router.route() -> ... -> InterceptorN
     * </p>
     */
    private <T> RouteResult<T> doRoute(String routerId, Router router, Object request, Type targetType) {
        List<RouteInterceptor> interceptors = interceptorRegistry.getPolicies();
        // 快速路径：无拦截器时直接调用路由器，减少调用栈深度
        if (interceptors == null || interceptors.isEmpty()) {
            if (router == null) {
                return RouteResult.unmatch();
            }
            return targetType != null ? router.route(request, targetType) : router.route(request);
        }

        // 拦截逻辑封装
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
        return doTrace(routerId, router, request);
    }

    /**
     * 获取原始配置对应的路由器
     */
    private Router getRouterByConfig(String rawConfig) {
        if (StringUtil.isBlank(rawConfig)) {
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
        Router router = getRouterByConfig(rawConfig);
        return doRoute("raw-config", router, request, null);
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
        return routeByConfig(rawConfig, request, (Type) targetType);
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
    public <T> RouteResult<T> routeByConfig(String rawConfig, Object request, Type targetType) {
        Router router = getRouterByConfig(rawConfig);
        return doRoute("raw-config", router, request, targetType);
    }

    /**
     * 执行路由并转换结果类型（针对原始配置）
     */
    public <T> RouteResult<T> routeByConfig(String rawConfig, Object request, TypeReference<T> typeReference) {
        return routeByConfig(rawConfig, request, typeReference != null ? typeReference.getType() : null);
    }

    /**
     * 统一路由追踪逻辑，追踪结果始终以底层 router.trace() 为主体。
     */
    private <T> RouteTrace<T> doTrace(String routerId, Router router, Object request) {
        List<RouteInterceptor> interceptors = interceptorRegistry.getPolicies();
        List<TraceableRouteInterceptor> observers = collectTraceObservers(interceptors);
        RouteTraceObservation<T> beforeObservation = new RouteTraceObservation<>(routerId, router, request, null);
        List<RouteTraceEvent> beforeEvents = new ArrayList<>();
        for (TraceableRouteInterceptor observer : observers) {
            beforeEvents.add(captureTraceEvent(beforeObservation, observer, true));
        }

        RouteTrace<T> trace = router != null ? router.trace(request) : emptyTrace();
        for (RouteTraceEvent event : beforeEvents) {
            trace.addEvent(event);
        }

        RouteTraceObservation<T> afterObservation = new RouteTraceObservation<>(routerId, router, request, trace);
        for (int i = observers.size() - 1; i >= 0; i--) {
            trace.addEvent(captureTraceEvent(afterObservation, observers.get(i), false));
        }
        return trace;
    }

    private List<TraceableRouteInterceptor> collectTraceObservers(List<RouteInterceptor> interceptors) {
        List<TraceableRouteInterceptor> observers = new ArrayList<>();
        if (interceptors == null || interceptors.isEmpty()) {
            return observers;
        }
        for (RouteInterceptor interceptor : interceptors) {
            if (interceptor instanceof TraceableRouteInterceptor) {
                observers.add((TraceableRouteInterceptor) interceptor);
            }
        }
        return observers;
    }

    private <T> RouteTraceEvent captureTraceEvent(RouteTraceObservation<T> observation,
                                                  TraceableRouteInterceptor observer,
                                                  boolean beforePhase) {
        try {
            Object detail = beforePhase ? observer.beforeTrace(observation) : observer.afterTrace(observation);
            return new RouteTraceEvent(
                    observer.getClass().getSimpleName(),
                    beforePhase ? "before" : "after",
                    detail);
        } catch (Exception e) {
            return new RouteTraceEvent(
                    observer.getClass().getSimpleName(),
                    beforePhase ? "before-error" : "after-error",
                    e.getMessage());
        }
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
        return doTrace("raw-config", router, request);
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
     * 执行路由并转换结果类型（针对编程式构建的 RoutePolicy）
     *
     * @param policy     路由策略对象
     * @param request    请求对象
     * @param targetType 目标类型
     * @param <T>        结果类型
     * @return 路由结果
     */
    public <T> RouteResult<T> routeByPolicy(RoutePolicy policy, Object request, Class<T> targetType) {
        return routeByPolicy(policy, request, (Type) targetType);
    }

    /**
     * 执行路由并转换结果类型（针对编程式构建的 RoutePolicy）
     */
    public <T> RouteResult<T> routeByPolicy(RoutePolicy policy, Object request, Type targetType) {
        Router router = getRouter(policy);
        return doRoute(policy != null ? policy.getId() : null, router, request, targetType);
    }

    /**
     * 执行路由并转换结果类型（针对编程式构建的 RoutePolicy）
     */
    public <T> RouteResult<T> routeByPolicy(RoutePolicy policy, Object request, TypeReference<T> typeReference) {
        return routeByPolicy(policy, request, typeReference != null ? typeReference.getType() : null);
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
        return doTrace(policy != null ? policy.getId() : null, router, request);
    }

    /**
     * 路由管理器构造器
     */
    public static class Builder {

        private RouterFactoryRegistry factoryRegistry;
        private ConfigManager configManager;
        private RoutePolicyParser configParser;
        // 默认配置前缀从全局引导配置中获取，支持通过 RouterBootstrap 全局统一配置
        private String configPrefix = RouterBootstrap.global().getConfigPrefix();
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
            if (StringUtil.isNotBlank(configPrefix)) {
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
            RouterFactoryRegistry finalRegistry = buildFactoryRegistry();
            ConfigManager configManager = resolveConfigManager();
            RoutePolicyParser parser = resolveConfigParser();
            RouteInterceptorRegistry interceptorRegistry = buildInterceptorRegistry();

            RoutingManager manager = new RoutingManager(
                    finalRegistry,
                    configManager,
                    parser,
                    configPrefix,
                    interceptorRegistry
            );
            // 每一个 RoutingManager 实例都应该持有绑定自己上下文的复合工厂，防止其退化到 global 查找引发的加载循环或逻辑隔离失效
            finalRegistry.register(new CompositeRouterFactory(manager));

            return manager;
        }

        /**
         * 构建工厂注册表。
         * <p>
         * 加载顺序（优先级由低到高）：
         * 1. 自动扫描 com.team4u.framework.router 包下的默认工厂。
         * 2. 通过 ServiceLoader 加载 SPI 定义的工厂。
         * 3. 加载通过 {@link #addFactory(RouterFactory)} 或 {@link #factoryRegistry(RouterFactoryRegistry)} 手动指定的工厂。
         * </p>
         */
        private RouterFactoryRegistry buildFactoryRegistry() {
            RouterFactoryRegistry finalRegistry = new RouterFactoryRegistry();
            // 先加载默认实现的工厂
            PolicyScanner.scanAndRegister(finalRegistry, "com.team4u.framework.router");
            // 再加载 SPI 工厂
            PolicyScanner.registerFromServiceLoader(finalRegistry);

            // 最后叠加手动指定的工厂（覆盖自动发现的同名工厂）
            RouterFactoryRegistry sourceRegistry = this.factoryRegistry != null
                    ? this.factoryRegistry
                    : RouterFactoryRegistry.global();
            finalRegistry.addAll(sourceRegistry);

            return finalRegistry;
        }

        /**
         * 解析配置管理器
         */
        private ConfigManager resolveConfigManager() {
            return this.configManager != null ? this.configManager : ConfigManager.global();
        }

        /**
         * 解析配置解析器
         * <p>
         * 优先级：手动传入 > SPI 发现 > 默认实现
         * </p>
         */
        private RoutePolicyParser resolveConfigParser() {
            if (this.configParser != null) {
                return this.configParser;
            }

            RoutePolicyParser spiParser = ServiceLoaderUtil.loadFirstAvailable(RoutePolicyParser.class);
            if (spiParser != null) {
                return spiParser;
            }

            return new DefaultRoutePolicyParser();
        }

        /**
         * 构建拦截器注册表
         */
        private RouteInterceptorRegistry buildInterceptorRegistry() {
            RouteInterceptorRegistry registry = this.interceptorRegistry;

            if (registry == null) {
                registry = useGlobalInterceptors
                        ? RouteInterceptorRegistry.global()
                        : new RouteInterceptorRegistry();
            }

            // 如果没有手动指定过注册中心，则尝试自动扫描
            if (this.interceptorRegistry == null) {
                registry.autoScan();
            }

            return registry;
        }
    }
}
