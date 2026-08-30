# Team4u 1.0 不兼容变更说明

| 版本 | 不兼容变更 | 迁移方案 |
| :--- | :--- | :--- |
| 1.0 | Mask 脱敏拆分为核心、Jackson 适配器与动态配置；未知策略快速失败 | 引入 `team4u-mask-jackson` 以获取 `com.team4u.framework.mask.jackson` 相关类；引入 `team4u-mask-config` 以支持动态配置规则。`MaskBootstrap` 迁移至 `com.team4u.framework.mask.config.MaskBootstrap`。未知、null、空串或空白字符的策略名称将抛出 `IllegalArgumentException`；保留明文请显式指定 `NONE`。 |
| 1.0 | 路由声明式代理 API 移至 `team4u-router-proxy` | 引入 `team4u-router-proxy` 获取 `@Routed`、`@RouteContext`、`RoutedProxyFactory`、`RoutedBeanLocator`、`BeanResolver` 与 `RoutedMethodInterceptor`；所有类名保持不变。`team4u-router` 保持轻量核心，负责 `RoutingManager`、路由策略、Trace 与拦截器；翻译器仅依赖路由核心。 |
| 1.0 | 契约翻译器不再传递 router-proxy 与 JSON Provider | 引入 `team4u-translator` 进行契约与响应翻译；仅在需要声明式路由代理时单独引入 `team4u-router-proxy`；使用 JSON 路由策略时显式引入 `team4u-serializer-jackson` 或注册自定义 `JsonSerializerPolicy`。 |
| 1.0 | 类型化 Space API 移至 `team4u-kv-space`，`HotSwapStore` 不再实现代理 `Swappable` | 引入 `team4u-kv-space` 获取 `Space`、`Spaces` 与 `SpacePolicy`。将 `HotSwapStore.wrap(...)` 返回值强转为 `com.team4u.framework.kv.HotSwap` 而非代理 `Swappable`；代理接口集合在包装时确定。 |
| 1.0 | `team4u-proxy` 不再传递 ByteBuddy 依赖 | 纯接口代理无需任何调整。对具体类生成代理时，应用需显式引入 `net.bytebuddy:byte-buddy`；该规则同样适用于 `team4u-log-governance` 与 `team4u-retry-proxy`。`team4u-config-proxy` 自身携带 ByteBuddy 运行时依赖，使用配置类代理时无需额外引入。 |
| 1.0 | 日志拆分为核心与治理模块（`team4u-log` / `team4u-log-governance`） | 纯日志输出引入 `team4u-log`，类名保持不变，默认输出 toString 格式；全局引导、Jackson 序列化、配置热更新、动态脱敏、方法追踪与 Spring 集成引入 `team4u-log-governance`。治理模块在运行时传递提供 Jackson Provider。 |
| 1.0 | 日志 reset 与序列化语义变更 | `LogEngine.reset()` 不再停止 `LogBootstrap` 治理生命周期；请先显式调用 `LogBootstrap.stop()`。核心 `LogEngine.toJson` 默认以 plain-text 格式输出；治理模块自动装配 Jackson 并在需要时提供标准 JSON。 |
| 1.0 | 核心主入口统一采用裸 ArtifactId，适配器采用明确后缀 | 直接依赖由根 BOM 管理的具体构件（如 `team4u-config`, `team4u-kv`, `team4u-lease`, `team4u-log`, `team4u-retry`, `team4u-ratelimiter`, `team4u-singleflight` 等）。裸坐标不再作为聚合器使用。 |
| 1.0 | 后台托管重试与配置驱动策略移出 `retry` 核心 | 引入 `team4u-retry-managed` 获取 `ManagedRetries`、`ManagedRetryClient`、托管记录/存储 SPI 与 `ManagedSubmitResult`；引入 `team4u-retry-config` 获取 `DynamicRetryPolicyRegistry`。`Retries` 仅保留 `INLINE` 进程内重试。 |
| 1.0 | 根 POM 是整个工程唯一的 BOM | 在 `<dependencyManagement>` 中引入 `com.team4u:team4u-framework:type=pom`；不再提供独立的 BOM 构件。 |
| 1.0 | 移除 `ConfigManager.Builder.configBinder(...)`；`createProxy` 必须提供 `ConfigProxyCreator` | 静态 POJO 绑定请直接使用 `DefaultConfigBinder.bind(...)`；引入 `team4u-config-proxy` 以自动通过 ServiceLoader 发现创建器，或显式注入 `ConfigProxyCreator`。`createProxy` 不再静默降级为绑定 POJO。 |
| 1.0 | 配置代理与 Spring 适配移出配置核心 | 引入 `team4u-config-proxy` 获取 `ConfigProxyFactory` 与 `SnapshotAware`。引入 `team4u-config-spring` 并显式导入 `Team4uConfigConfiguration`。 |
| 1.0 | `ConfigManager.global()` 延迟初始化，`ConfigBootstrap` 自动刷新已初始化的全局实例 | 建议在应用启动时完成引导注册；在锁定前的后期注册无需调用方手动调用 refresh 即可自动生效。 |
| 1.0 | 序列化门面 API 不再默认携带 Jackson 运行时 Provider | 显式引入 `com.team4u:team4u-serializer-jackson`，或通过 ServiceLoader 注册自定义的 `JsonSerializerPolicy`。上游基础库均不自带运行时 Provider（日志治理模块除外）。 |
| 1.0 | JDBC 构建工具由 `team4u-base` 移至 `team4u-base-jdbc` | 引入 `com.team4u:team4u-base-jdbc`；所有 JDBC 相关类名与包路径保持不变。 |
| 1.0 | Criterion 通配符匹配由 Spring AntPathMatcher 切换为 Base PathPatternMatcher | DSL 表达式语法与 53 组测试矩阵行为完全一致；纯 Java 路径匹配可直接调用 Base 的 `PathPatternMatcher`。 |
| 1.0 | 移除 `Team4uCriterionAutoConfiguration` | Criterion 模块不再自带 Spring 自动配置；如需将全局单例注入容器，请在应用配置中显式注册。 |
| 1.0 | 消费者契约测试验证 8 个标准消费者场景 | `consumer-it` / `release-contracts` 运行 minimal、config-core、serializer-api、serializer-jackson、interface-proxy、log-governance、ratelimiter-core 与 singleflight-jackson（共 8 个用例），严格验证各模块隔离边界。 |
| 1.0 | `team4u-ratelimiter` 单体拆分为 core / proxy / spring | 引入 `team4u-ratelimiter` 获取核心引擎与门面，`team4u-ratelimiter-proxy` 获取 `@RateLimit` 注解拦截，`team4u-ratelimiter-spring` 获取 `@EnableRateLimit` 自动装配。JSON 规则需应用显式提供 JSON Provider。 |
| 1.0 | `team4u-singleflight` 单体拆分为 core / proxy / spring | 引入 `team4u-singleflight` 获取核心引擎与状态机，`team4u-singleflight-proxy` 获取 `@SingleFlight`，`team4u-singleflight-spring` 获取 `@EnableSingleFlight`。核心携带轻量 `jackson-databind` 编译依赖以保障持久化信封协议，规则解析仍需应用显式提供 JSON Provider。 |
| 1.0 | `NamedKvStoreRegistry` 与 `NamedKvStore` 移至 `team4u-kv-space` | 类名保持不变（`com.team4u.framework.kv.*`）；引入 `team4u-kv-space`（或传递依赖它的 id / ratelimiter / singleflight）以继续使用命名存储。 |
| 1.0 | 新增 `team4u-id` 与 `team4u-proxy-spring` 构件 | `team4u-id` 提供基于 KV `CounterCapable` 的配置驱动序号生成；`team4u-proxy-spring` 提供注解代理 Spring 装配模板基类（`AnnotationProxyBeanPostProcessor`），不含 ByteBuddy。 |
| 1.0 | 最终 Reactor 与根 BOM 统一管理 48 个发布模块 | 根 POM 作为唯一的 Parent、Aggregator 与 BOM，管理 48 个具体的发布叶子模块。直接依赖具体模块即可。 |
