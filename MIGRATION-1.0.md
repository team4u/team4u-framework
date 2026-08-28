# Team4u 1.0 Migration Guide

## Maven dependency management

Team4u 1.0 publishes one dependency-management POM. Import the root POM; there is no separate BOM artifact.

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

## CI and consumer contract profiles

Developers can run the currently green external-consumer set with:

```bash
mvn -Pconsumer-it -DskipTests verify
```

The default set covers minimal base, config-core, provider-free serializer API, explicit Jackson provider, and JDK interface proxy consumers. Since Task 9, `consumer-config-core` proves that scalar config and explicit binding have no proxy, ByteBuddy, Jackson, or Spring runtime edge.

The release baseline gate is:

```bash
mvn -Prelease-contracts -DskipTests verify
```

It runs the same five active consumers, executes their mains, and validates or records each runtime dependency tree.

## Config proxy creation

`ConfigManager.Builder.configBinder(...)` is removed; it never controlled live proxy construction. Use `DefaultConfigBinder.bind(...)` directly for a one-time bound POJO. `createProxy(...)` now resolves only `ConfigManager.Builder.proxyCreator(...)`, followed by a single ServiceLoader implementation. With neither source it fails fast and recommends `com.team4u:team4u-config-proxy` or a custom `ConfigProxyCreator`; a bound POJO is never returned as a substitute proxy.
Add `com.team4u:team4u-config-proxy` to let `ConfigManager.createProxy(...)` discover `ServiceLoaderConfigProxyCreator` automatically; explicit `ConfigProxyCreator` injection remains supported. The first call to `ConfigManager.global()` now initializes the global manager, and `ConfigBootstrap` refreshes an already-initialized global after source, watcher, converter, or lock operations. Late registrations are therefore visible without a caller-side refresh.

## Lease runtime boundary

`team4u-lease-core` stays independent of Config, Retry, KV, Jackson, and Spring. Tests and logging implementations are no longer published transitively: JUnit and `slf4j-simple` are test-scoped in `lease-core`; `lease-jdbc` additionally keeps H2 test-scoped. `team4u-lease-test` intentionally keeps JUnit `provided` because it is a published test-contract artifact.

`team4u-lease-jdbc` publishes only its intended production edges: `lease-core`, `base`, `base-jdbc`, `serializer-json`, and `slf4j-api`. It never carries the Jackson provider. Applications using JSON attributes must add `team4u-serializer-jackson` or provide another registered `JsonSerializerPolicy` themselves.

## Explicit serializer provider choice

Applications using JSON APIs must choose a provider explicitly. Add `com.team4u:team4u-serializer-jackson` to the application, or provide/register a custom `JsonSerializerPolicy` through `META-INF/services/com.team4u.framework.serializer.json.JsonSerializerPolicy`. Depending only on `team4u-serializer-json` is supported, but the first non-null/non-empty JSON call fails fast with an `IllegalStateException` naming both choices. The same requirement applies to JSON paths in config-core, retry-core, kv-core/kv-lifecycle, lease-jdbc, router, translator, mask, and log. Until Tasks 15 and 17, mask and log may directly depend on Jackson for their current adapters but never pass `team4u-serializer-jackson`. `team4u-retry-lease-runtime` permanently carries nonoptional Jackson for its durable-record integration and therefore supplies Jackson to consumers directly; this is distinct from an application-owned `JsonUtil` provider, and the artifact never passes `team4u-serializer-jackson`.

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
