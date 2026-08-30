# Team4u 1.0 迁移与升级指南

Team4u 1.0 统一发布单个依赖管理 POM。请直接在项目中引入根 POM，不再提供独立的 BOM 构件。工程最终的 Reactor 与根 BOM 统一管理 48 个具体的发布叶子模块。

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.team4u</groupId>
            <artifactId>team4u-framework</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 1. CI 与消费者契约验证配置

开发者可通过以下命令运行当前全绿的外部消费者测试套件：

```bash
mvn -Pconsumer-it -DskipTests verify
```

默认套件覆盖 8 个标准消费者用例：`minimal`（最小 base 依赖）、`config-core`（纯核心配置）、`serializer-api`（无 Provider 序列化门面）、`serializer-jackson`（显式引入 Jackson）、`interface-proxy`（JDK 接口代理）、`log-governance`（日志治理）、`ratelimiter-core`（纯核心限流）以及 `singleflight-jackson`（单飞合并与应用端 Jackson）。
- `config-core` 验证了标量配置与显式绑定无需引入 Proxy、ByteBuddy、Jackson 或 Spring 运行时；
- `log-governance` 仅依赖 BOM 管理的 `team4u-log-governance`，验证了运行时的传递 Jackson Provider，并确认 `LogBootstrap.start/stop` 可正常接管日志引擎；
- `ratelimiter-core` 验证了限流的三向拆分（纯核心无 Proxy/Spring 边缘）；
- `singleflight-jackson` 验证了单飞合并与应用端显式提供的 JSON Provider 配合（仅含 databind 边缘，不隐式传递 Provider）。

发布基线门禁命令为：

```bash
mvn -Prelease-contracts -DskipTests verify
```

该命令将运行相同的 8 个活跃消费者测试用例，执行其 Main 方法，并校验与记录各模块的运行时依赖树（48 个叶子模块、30 个代表性依赖形状）。

---

## 2. 配置代理创建机制变更

1. 移除了 `ConfigManager.Builder.configBinder(...)`（该方法原本并不控制动态代理构建）。对于一次性绑定的静态 POJO，请直接使用 `DefaultConfigBinder.bind(...)`。
2. `createProxy(...)` 现在仅通过 `ConfigManager.Builder.proxyCreator(...)` 或单一 `ServiceLoader` 实现进行解析。若两者均未提供，将快速失败并明确提示引入 `com.team4u:team4u-config-proxy` 或自定义 `ConfigProxyCreator`，绝不会静默降级返回静态绑定 POJO 作为代理替身。
3. 引入 `com.team4u:team4u-config-proxy` 可让 `ConfigManager.createProxy(...)` 自动发现 `ServiceLoaderConfigProxyCreator`；显式注入 `ConfigProxyCreator` 的方式依然支持。首次调用 `ConfigManager.global()` 时将完成全局管理器初始化，且 `ConfigBootstrap` 会在数据源、监听器、转换器或锁定操作后自动刷新已初始化的全局实例，后续注册无需调用方手动触发刷新。

---

## 3. 租约运行时边界

1. `team4u-lease` 保持对 Config、Retry、KV、Jackson 及 Spring 的独立解耦。测试与日志实现不再对外传递：JUnit 与 `slf4j-simple` 在 `lease` 核心中设为 `test` 范围；`team4u-lease-jdbc` 中 H2 同样设为 `test` 范围；`team4u-lease-test` 作为公开测试契约包，保留 JUnit 为 `provided`。
2. `team4u-lease-jdbc` 仅发布其必要的生产依赖：`team4u-lease`、`team4u-base`、`team4u-base-jdbc`、`team4u-serializer-json` 与 `slf4j-api`，绝不自带 Jackson Provider。使用 JSON 属性的应用需自行引入 `team4u-serializer-jackson` 或注册自定义的 `JsonSerializerPolicy`。

---

## 4. KV Space 与 HotSwap 拆分

1. `Space`、`Spaces` 与 `SpacePolicy` 已从核心移至 `team4u-kv-space` 模块。新构件依赖 `team4u-kv`、`team4u-policy` 与 `team4u-serializer-json`；使用类型化 JSON 空间的应用需显式引入该模块并提供 `team4u-serializer-jackson` 或自定义 `JsonSerializerPolicy`。`team4u-kv` 核心仅保留 `team4u-base` 与 `slf4j-api` 生产依赖。
2. `HotSwapStore.wrap(KvStore)` 返回的代理不再实现 `com.team4u.framework.proxy.support.Swappable`。如需进行原子热替换，请将实例强转为 `com.team4u.framework.kv.HotSwap`，调用 `hotswap(newDelegate)` 并自行管理返回的旧存储。代理固定实现 `KvStore` 与 `HotSwap`；仅在初始委托对象支持时才额外实现 `StoreWrapper` 与 `AutoCloseable`（接口集合在包装时确定，后续热替换不会改变接口契约）。

---

## 5. 路由声明式代理拆分

`@Routed`、`@RouteContext`、`RoutedProxyFactory`、`RoutedBeanLocator`、`BeanResolver` 与 `RoutedMethodInterceptor` 已由 `team4u-router` 移至 `team4u-router-proxy`；所有类的完整类名（FQCN）保持不变。
- 需要创建路由接口代理或解析路由 Bean 时，请显式引入 `com.team4u:team4u-router-proxy`。
- `team4u-router` 仅负责 `RoutingManager`、路由策略解析、Trace 与拦截器；`team4u-translator` 仅依赖路由核心，不引入代理、Bean 容器、ByteBuddy 或 JSON Provider。

---

## 6. 重试模块治理拆分

后台托管重试治理能力由 `team4u-retry` 移至 `team4u-retry-managed`，配置驱动重试策略移至 `team4u-retry-config`。

| 版本 | 变更/移除的 API | 迁移方案 |
| :--- | :--- | :--- |
| 1.0 | 移除 `Retries.managed(ManagedRetryClient)` | 使用 `team4u-retry-managed` 中的 `ManagedRetries.with(client)`；`Retries` 仅保留 `INLINE` 进程内重试。 |
| 1.0 | 迁移 `com.team4u.framework.retry.api.ManagedSubmitResult` | 改为使用 `com.team4u.framework.retry.managed.ManagedSubmitResult`。 |
| 1.0 | 迁移 `com.team4u.framework.retry.config.DynamicRetryPolicyRegistry` | 改为使用 `team4u-retry-config` 中的 `com.team4u.framework.retry.dynamic.DynamicRetryPolicyRegistry`。 |

---

## 7. 结构化日志核心与治理拆分

日志能力拆分为 `team4u-log`（无 Provider 的轻量流式日志核心）与 `team4u-log-governance`（包含引导、Jackson、动态脱敏、染色与 Spring 治理）。所有类名与包路径保持不变。

| 版本 | 变更/移除的 API | 迁移方案 |
| :--- | :--- | :--- |
| 1.0 | 日志拆分为核心与治理模块 | 纯日志输出使用 `team4u-log`；全局引导、治理与 Jackson 集成使用 `team4u-log-governance`。 |
| 1.0 | `LogBootstrap` 迁移构件 | 引入 `team4u-log-governance`；类名 `com.team4u.framework.log.LogBootstrap` 保持不变。 |
| 1.0 | Jackson、Config、Mask、Proxy、Criterion 与 Spring 集成迁移构件 | 引入 `team4u-log-governance`；`team4u-log` 核心不含上述依赖。 |
| 1.0 | `LogEngine.reset()` 不再停止治理生命周期 | 请先显式调用 `LogBootstrap.stop()`；核心 reset 仅重置 Appender、拦截器与序列化器状态。 |
| 1.0 | `LogEngine.toJson(LogEvent)` 默认文本化 | 核心默认使用 `toString` 格式化；需要标准 JSON 时请注册自定义序列化器或引入日志治理模块。 |
| 1.0 | 日志治理自带 Jackson Provider | 依赖 `team4u-log-governance` 即可，它会在运行时传递提供 `team4u-serializer-jackson`。 |

---

## 8. Bean 容器 Spring 适配拆分

`com.team4u.framework.bean.provider.SpringBeanContainer` 保持完整类名，但已移至 `team4u-bean-spring`。纯 Java 本地容器使用者仅需依赖 `team4u-bean`，完全无 Spring 编译与运行时依赖。

Spring 用户请引入 `com.team4u:team4u-bean-spring`，移除手动声明的 `@Bean SpringBeanContainer`，并显式 `@Import` 配置类：

```java
@Configuration
@Import(Team4uBeanConfiguration.class)
public class ApplicationConfiguration {
}
```

`team4u-retry-spring` 现已依赖 `team4u-bean-spring`，其 `RetrySpringConfiguration` 会自动导入 `Team4uBeanConfiguration`，因此使用 `@EnableRetry` 时依然能自动注入单一适配器。

| 版本 | 变更/移除的 API | 迁移方案 |
| :--- | :--- | :--- |
| 1.0 | `SpringBeanContainer` 迁移模块 | 类名保持不变；引入 `team4u-bean-spring` 并使用 `@Import(Team4uBeanConfiguration.class)`。 |
| 1.0 | 移除 `RetrySpringConfiguration.springBeanContainer()` | 使用 `@EnableRetry` 即可，导入的公共配置会自动注册 `SpringBeanContainer`。 |

---

## 9. 数据脱敏适配与动态配置拆分

1. `team4u-mask` 为纯 Java 核心脱敏构件，仅依赖 `team4u-base` 与 `team4u-policy`。
2. Jackson 序列化脱敏请引入 `com.team4u:team4u-mask-jackson`（包名 `com.team4u.framework.mask.jackson` 保持不变）。
3. 配置驱动动态规则请引入 `com.team4u:team4u-mask-config`；该模块依赖配置核心与 JSON 序列化门面，应用需提供 `team4u-serializer-jackson` 或注册自定义 `JsonSerializerPolicy`。
4. `MaskRuleRepository` 保留类名 `com.team4u.framework.mask.config.MaskRuleRepository` 并实现了核心 `MaskRuleResolver` SPI。`MaskBootstrap` 迁移至 `com.team4u.framework.mask.config.MaskBootstrap`。
5. 未知脱敏策略键、null、空串及空白字符现在将严格抛出 `IllegalArgumentException`；仅显式配置 `NONE` 会保留明文原始值。

---

## 10. 显式选择 JSON 序列化 Provider

使用 JSON 序列化 API 的应用必须显式选择 Provider：
- 在应用依赖中引入 `com.team4u:team4u-serializer-jackson`；
- 或通过 SPI（`META-INF/services/com.team4u.framework.serializer.json.JsonSerializerPolicy`）注册自定义的 `JsonSerializerPolicy`。

仅依赖 `team4u-serializer-json` 时编译正常，但首次进行非空 JSON 调用时将快速失败并抛出明确的 `IllegalStateException` 指引。该要求同样适用于 `team4u-config`、`team4u-retry`、`team4u-kv-space`、`team4u-lease-jdbc`、`team4u-router`、`team4u-translator` 与 `team4u-mask-config` 中的 JSON 解析链路。

---

## 11. Base JDBC 与 Spring Bean 查找

1. 使用 `JdbcUtil`、`InsertBuilder`、`UpdateBuilder`、`SqlBuilder` 或 `SqlExpression` 的应用必须显式引入 `com.team4u:team4u-base-jdbc`；包名与类名保持不变，`team4u-base` 核心不再携带 JDBC 与 Spring。
2. 彻底删除了 `com.team4u.framework.base.util.SpringUtil`。请统一替换为 `BeanManager.getInstance().getBean(...)` 并配合 `team4u-bean-spring`。

---

## 12. 类代理的按需引入 ByteBuddy

`team4u-proxy` 默认支持纯 JDK 动态接口代理（无需 ByteBuddy）。当需要对具体类（Class）生成代理时，请按需显式添加 ByteBuddy 依赖：

```xml
<dependency>
    <groupId>net.bytebuddy</groupId>
    <artifactId>byte-buddy</artifactId>
    <version>1.14.12</version>
</dependency>
```

该规则同样适用于 `LogProxyFactory` 与 `RetryProxyFactory`。`team4u-config-proxy` 是特例：由于其专门构建配置类代理，已将 ByteBuddy 作为显式运行时依赖打包，使用配置代理时无需重复引入。

---

## 13. 核心主入口统一裸 Artifact 命名规范

在 Team4u 1.0 中，各业务能力族（family）的主入口核心模块统一采用**裸 ArtifactId**（`team4u-config`, `team4u-kv`, `team4u-lease`, `team4u-log`, `team4u-ratelimiter`, `team4u-retry`, `team4u-singleflight`），运行时扩展与适配器携带明确后缀（`team4u-config-proxy`, `team4u-kv-space`, `team4u-log-governance`, `team4u-ratelimiter-spring`, `team4u-retry-managed` 等）。

裸坐标不再承担聚合器角色；全工程采用标准的二维目录结构 `modules/<family>/<variant>`，根 `team4u-framework` POM 作为整个项目唯一的 Parent、Aggregator 和 BOM。

---

## 14. 归并功能与新模块

- **`team4u-proxy-spring`**：注解代理 Spring 装配模板（`AnnotationProxyBeanPostProcessor` 抽象基类），仅依赖 `team4u-proxy` 与 `spring-context`/`spring-aop`，绝不携带 ByteBuddy，为 `ratelimiter-spring` / `singleflight-spring` 提供通用底座。
- **`team4u-ratelimiter` / `-proxy` / `-spring`（三向拆分）**：旧版单体 `team4u-ratelimiter` 已移除。核心提供规则模型、4 种限流算法与门面；proxy 模块提供 `@RateLimit` 注解拦截；spring 模块提供 `@EnableRateLimit` 自动装配。
- **`team4u-singleflight` / `-proxy` / `-spring`（三向拆分）**：旧版单体 `team4u-singleflight` 已移除。核心提供规则模型、状态协调机与并发合并门面；proxy 模块提供 `@SingleFlight`；spring 模块提供 `@EnableSingleFlight`。核心携带轻量 `jackson-databind` 编译依赖以保障持久化会话信封协议，规则解析依然走 `JsonUtil` 门面。
- **命名存储注册表移至 `team4u-kv-space`**：`NamedKvStore` / `NamedKvStoreRegistry` 保持原类名，归属到 `team4u-kv-space` 中。
- **`team4u-id`**：基于 KV 原子计数器能力（`CounterCapable`）的配置驱动序号生成模块，支持分组重置、循环使用、本地号段加速与模板单号。

---

## 15. 通配符匹配器平滑过渡

Criterion 的 `like` 通配语法已切换至内部实现的 `com.team4u.framework.base.pattern.PathPatternMatcher`（Ant-Style 语义），彻底移除对 Spring 的生产依赖。53 组基准用例测试结果与原有行为完全一致。

同时移除了 Criterion 的 Spring 自动配置类 `Team4uCriterionAutoConfiguration`，如需将 `Criteria.global()` 等单例注入 Spring 容器，请在应用配置类中自行声明注册。
