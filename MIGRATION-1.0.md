# Team4u 1.0 Migration Guide

Team4u 1.0 publishes one dependency-management POM. Import the root POM; there is no separate BOM artifact. The final reactor and root BOM manage 40 concrete framework leaves.

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

## CI and consumer contract profiles

Developers can run the currently green external-consumer set with:

```bash
mvn -Pconsumer-it -DskipTests verify
```

The default set covers minimal base, config-core, provider-free serializer API, explicit Jackson provider, JDK interface proxy, and log-governance consumers. Since Task 9, `consumer-config-core` proves that scalar config and explicit binding have no proxy, ByteBuddy, Jackson, or Spring runtime edge. Since Task 17, `consumer-log-governance` depends only on BOM-managed `team4u-log-governance`, proves the transitive Jackson provider at runtime, and verifies that `LogBootstrap.start/stop` exchanges the engine as the documented owner.

The release baseline gate is:

```bash
mvn -Prelease-contracts -DskipTests verify
```

It runs the same six active consumers, executes their mains, and validates or records each runtime dependency tree.

Task 18 adds no functional migration and does not change `1.0.0-SNAPSHOT`. It aligns performance wording with the committed JMH evidence and adds the unpublished standalone benchmark project and release-evidence gates. Release evidence remains incomplete until the sequential JDK 8/11/17/21 CI matrix passes on GitHub Actions; this local environment only has JDK 21. No production release or version change should be made from that local-only evidence.

## Config proxy creation

`ConfigManager.Builder.configBinder(...)` is removed; it never controlled live proxy construction. Use `DefaultConfigBinder.bind(...)` directly for a one-time bound POJO. `createProxy(...)` now resolves only `ConfigManager.Builder.proxyCreator(...)`, followed by a single ServiceLoader implementation. With neither source it fails fast and recommends `com.team4u:team4u-config-proxy` or a custom `ConfigProxyCreator`; a bound POJO is never returned as a substitute proxy.
Add `com.team4u:team4u-config-proxy` to let `ConfigManager.createProxy(...)` discover `ServiceLoaderConfigProxyCreator` automatically; explicit `ConfigProxyCreator` injection remains supported. The first call to `ConfigManager.global()` now initializes the global manager, and `ConfigBootstrap` refreshes an already-initialized global after source, watcher, converter, or lock operations. Late registrations are therefore visible without a caller-side refresh.

## Lease runtime boundary

`team4u-lease-core` stays independent of Config, Retry, KV, Jackson, and Spring. Tests and logging implementations are no longer published transitively: JUnit and `slf4j-simple` are test-scoped in `lease-core`; `lease-jdbc` additionally keeps H2 test-scoped. `team4u-lease-test` intentionally keeps JUnit `provided` because it is a published test-contract artifact.

`team4u-lease-jdbc` publishes only its intended production edges: `lease-core`, `base`, `base-jdbc`, `serializer-json`, and `slf4j-api`. It never carries the Jackson provider. Applications using JSON attributes must add `team4u-serializer-jackson` or provide another registered `JsonSerializerPolicy` themselves.

## KV space and hot swap split

`Space`, `Spaces`, and `SpacePolicy` moved from `team4u-kv-core` to `team4u-kv-space`. The new artifact depends on kv-core, policy, and serializer-json; applications using typed JSON spaces must add it and explicitly choose `team4u-serializer-jackson` or another registered `JsonSerializerPolicy`. `team4u-kv-core` now carries only `team4u-base` and `slf4j-api` production dependencies.

`HotSwapStore.wrap(KvStore)` still returns `KvStore`, but its proxy no longer implements `com.team4u.framework.proxy.support.Swappable`. For direct atomic replacement, cast to `com.team4u.framework.kv.HotSwap`, call `hotswap(newDelegate)`, and manage the returned old store yourself. The proxy always implements `KvStore` and `HotSwap`; it additionally implements `StoreWrapper` and `AutoCloseable` only when the initial delegate does. That interface set is fixed at wrap time and cannot change after later swaps.

## Router declarative proxy split

`@Routed`, `@RouteContext`, `RoutedProxyFactory`, `RoutedBeanLocator`, `BeanResolver`, and `RoutedMethodInterceptor` moved from `team4u-router` to `team4u-router-proxy`; every FQCN is unchanged. Add `com.team4u:team4u-router-proxy` when creating routed interface proxies or resolving routed beans. Keep `team4u-router` for `RoutingManager`, routing policy parsing, trace, and interceptors; `team4u-translator` remains router-core-only and never passes proxy, bean, config-proxy, ByteBuddy, or a JSON provider. Router core never publishes proxy, bean, config-proxy, ByteBuddy, or Jackson production dependencies.
## Retry module split

Managed retry governance moved from `team4u-retry-core` to `team4u-retry-managed`, and config-driven retry policies moved to `team4u-retry-config`.

| Version | Removed or moved API | Migration |
| --- | --- | --- |
| 1.0 | Removed `Retries.managed(ManagedRetryClient)` | Use `ManagedRetries.with(client)` from `team4u-retry-managed`; `Retries` supports INLINE only. |
| 1.0 | Moved `com.team4u.framework.retry.api.ManagedSubmitResult` | Use `com.team4u.framework.retry.managed.ManagedSubmitResult`. |
| 1.0 | Moved `com.team4u.framework.retry.config.DynamicRetryPolicyRegistry` | Use `com.team4u.framework.retry.dynamic.DynamicRetryPolicyRegistry` from `team4u-retry-config`. |

## Log core and governance split

The old `com.team4u:team4u-log` artifact is removed with no compatibility/bridge artifact. All production and test FQCNs are unchanged; packages simply moved from `team4u-log` to `team4u-log-core` or `team4u-log-governance` according to ownership.

| Version | Removed or moved API | Migration |
| --- | --- | --- |
| 1.0 | Removed `team4u-log` with no replacement artifact | Use `team4u-log-core` for provider-free logging and `team4u-log-governance` for bootstrap and governance. |
| 1.0 | `LogBootstrap` moved artifact | Add `team4u-log-governance`; its FQCN `com.team4u.framework.log.LogBootstrap` is unchanged. |
| 1.0 | Jackson, Config, Mask, Proxy, Criterion, and Spring integrations moved artifact | Add `team4u-log-governance`; `team4u-log-core` has no corresponding dependency or source edge. |
| 1.0 | `LogEngine.reset()` no longer stops governance | Call `LogBootstrap.stop()` first; core reset resets appender, interceptors, and serializer state without changing bootstrap ownership. |
| 1.0 | `LogEngine.toJson(LogEvent)` may be plain text | Core defaults to `toString`; install a custom serializer or use governance Jackson when JSON is required. |
| 1.0 | Governance carries the Jackson provider | Depend only on `team4u-log-governance`; it supplies `team4u-serializer-jackson` and Jackson transitively at runtime. |

## Bean Spring adapter split

`com.team4u.framework.bean.provider.SpringBeanContainer` keeps its FQCN but moved from `team4u-bean` to `team4u-bean-spring`. Pure Java local-container users keep only `team4u-bean`; it has no Spring compile, test, runtime, or source edge.

Spring users add `com.team4u:team4u-bean-spring`, remove manual `@Bean SpringBeanContainer` declarations, and import the plain configuration explicitly:

```java
@Configuration
@Import(Team4uBeanConfiguration.class)
public class ApplicationConfiguration {
}
```

`team4u-retry-spring` now depends on `team4u-bean-spring`; its `RetrySpringConfiguration` imports `Team4uBeanConfiguration`, so `@EnableRetry` still supplies exactly one adapter without application-side manual wiring.

| Version | Removed or moved API | Migration |
| --- | --- | --- |
| 1.0 | Moved `SpringBeanContainer` from `team4u-bean` | FQCN is unchanged; add `team4u-bean-spring` and replace manual `@Bean` wiring with `@Import(Team4uBeanConfiguration.class)`. |
| 1.0 | Removed `RetrySpringConfiguration.springBeanContainer()` | Use `@EnableRetry`; the imported shared configuration registers one `SpringBeanContainer`. |

## Mask adapter and dynamic config split

`team4u-mask` is now the core artifact and still depends only on `team4u-base` and `team4u-policy`. Add `com.team4u:team4u-mask-jackson` for the unchanged `com.team4u.framework.mask.jackson` classes and Jackson serialization. Add `com.team4u:team4u-mask-config` for config-driven rules; it depends on config-core and serializer-json, and the application must supply `team4u-serializer-jackson` or another registered `JsonSerializerPolicy`.

`MaskRuleRepository` keeps `com.team4u.framework.mask.config.MaskRuleRepository` and now implements the core `MaskRuleResolver` SPI. `MaskBootstrap` moved without a compatibility class from `com.team4u.framework.mask.MaskBootstrap` to `com.team4u.framework.mask.config.MaskBootstrap`; update the import and add `team4u-mask-config`. Jackson serialization reads dynamic rules through the core global resolver, so it does not depend on mask-config.

Unknown mask policy keys, null, empty, and whitespace keys now throw `IllegalArgumentException`. Only explicit `NONE` preserves the original value; update accidental fallback usages to register a real `MaskPolicy`.

## Explicit serializer provider choice

Applications using JSON APIs must choose a provider explicitly. Add `com.team4u:team4u-serializer-jackson` to the application, or provide/register a custom `JsonSerializerPolicy` through `META-INF/services/com.team4u.framework.serializer.json.JsonSerializerPolicy`. Depending only on `team4u-serializer-json` is supported, but the first non-null/non-empty JSON call fails fast with an `IllegalStateException` naming both choices. The same requirement applies to JSON paths in config-core, retry-core, kv-space/kv-lifecycle, lease-jdbc, router, translator, and mask-config. `team4u-mask-jackson` owns direct Jackson API for its serializer adapter; it never passes `team4u-serializer-jackson`. `team4u-retry-lease-runtime` permanently carries nonoptional Jackson for its durable-record integration and therefore supplies Jackson to consumers directly; this is distinct from an application-owned `JsonUtil` provider, and the artifact never passes `team4u-serializer-jackson`. Log governance is the logging exception: depending on `team4u-log-governance` alone transitively supplies `team4u-serializer-jackson` for its bootstrap and `JsonUtil` runtime.

After importing it, depend on concrete Team4u artifacts without versions.

## Base JDBC and Spring bean lookup

Applications using `JdbcUtil`, `InsertBuilder`, `UpdateBuilder`, `SqlBuilder`, or `SqlExpression` must add `com.team4u:team4u-base-jdbc`; package and class names are unchanged and `team4u-base` no longer carries JDBC or Spring.

`com.team4u.framework.base.util.SpringUtil` is deleted. Replace it with `BeanManager.getInstance().getBean(...)` after registering a `BeanFactory`/bean provider compatible with `com.team4u.framework.bean.BeanManager`.

## Optional ByteBuddy for class proxies

`team4u-proxy` supports JDK interface proxies without ByteBuddy. Add ByteBuddy directly only when proxying a concrete class:

```xml
<dependency>
    <groupId>net.bytebuddy</groupId>
    <artifactId>byte-buddy</artifactId>
    <version>1.14.12</version>
</dependency>
```

The same rule applies to `LogProxyFactory.createProxy`, `LogProxyFactory.createDynamicProxy`, and `RetryProxyFactory.createProxy`. `team4u-config-proxy` is the exception: it always builds concrete class proxies, so it carries ByteBuddy as an explicit runtime dependency. Adding `team4u-config-proxy` alone is sufficient; do not add a second ByteBuddy dependency for config proxies.

The engine is attempted from the thread context loader, the target type's loader, and then `ProxyBuilder`'s defining loader. Child-first/plugin loaders that can define both the engine and ByteBuddy are supported. A JVM visibility boundary remains if `ProxyBuilder` is parent-defined, the engine is ordinary parent-delegated, and only a normal child loader carries ByteBuddy: a parent-defined engine class cannot resolve types visible only to that child. Place ByteBuddy in the parent visible to the engine, or use a loader that defines both.

## Removed grouping artifacts

The pure grouping artifacts `team4u-config`, `team4u-kv`, `team4u-lease`, `team4u-retry`, and `team4u-serializer` no longer exist. Replace each grouping dependency with the concrete artifact that provides the classes you use, managed by the root BOM.

## Wildcard matcher transition

Criterion's `like` syntax now delegates to `com.team4u.framework.base.pattern.PathPatternMatcher` with Team4u Ant-style semantics; the module no longer has a Spring production dependency. Observable behavior is unchanged across the locked 53-case matrix: `*` stays within one `/`-delimited segment, `?` matches one non-separator character, only an exact `**` segment crosses directories, `***` remains segment-local, and a backslash is an ordinary literal. Null behavior is adapter-owned: null pattern/null actual is true, null pattern only is false, and non-null pattern/null actual remains false to public Criterion callers.

Criterion's Spring-only `Team4uCriterionAutoConfiguration` was removed. Register `Criteria.global()`, `StandardCriterionParser.global()`, `CompilerRegistry.global()`, or `ValueConverterRegistry.global()` directly in application configuration if those singletons are needed as beans.
