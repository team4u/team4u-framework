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

The default set covers minimal base, provider-free serializer API, explicit Jackson provider, and JDK interface proxy consumers. `consumer-config-core` remains explicit until Task 9: after Task 7 its runtime tree is free of Jackson/ByteBuddy but still carries `team4u-proxy`.

The release baseline gate is:

```bash
mvn -Prelease-contracts -DskipTests verify
```

It runs the same four active consumers, executes their mains, and validates or records each runtime dependency tree. `consumer-config-core` joins when Task 9 removes its remaining proxy edge.

## Config proxy creation

`ConfigManager.Builder.configBinder(...)` is removed; it never controlled live proxy construction. Use `DefaultConfigBinder.bind(...)` directly for a one-time bound POJO. `createProxy(...)` now resolves only `ConfigManager.Builder.proxyCreator(...)`, followed by a single ServiceLoader implementation. With neither source it fails fast and recommends `com.team4u:team4u-config-proxy` or a custom `ConfigProxyCreator`; a bound POJO is never returned as a substitute proxy.

Until Task 9 publishes `team4u-config-proxy`, explicitly provide a `ConfigProxyCreator`; `TestConfigContext` does this internally and remains usable. The first call to `ConfigManager.global()` now initializes the global manager, and `ConfigBootstrap` refreshes an already-initialized global after source, watcher, converter, or lock operations. Late registrations are therefore visible without a caller-side refresh.

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

The same rule applies to the concrete-class proxy paths behind `ConfigProxyFactory.createLiveProxy`, `ConfigProxyFactory.createPinnedProxy`, `LogProxyFactory.createProxy`, `LogProxyFactory.createDynamicProxy`, and `RetryProxyFactory.createProxy`. Interface-only paths in those artifacts do not require ByteBuddy; if the entry point accepts and uses a concrete target class, its path does.

The engine is attempted from the thread context loader, the target type's loader, and then `ProxyBuilder`'s defining loader. Child-first/plugin loaders that can define both the engine and ByteBuddy are supported. A JVM visibility boundary remains if `ProxyBuilder` is parent-defined, the engine is ordinary parent-delegated, and only a normal child loader carries ByteBuddy: a parent-defined engine class cannot resolve types visible only to that child. Place ByteBuddy in the parent visible to the engine, or use a loader that defines both.

## Removed grouping artifacts

The pure grouping artifacts `team4u-config`, `team4u-kv`, `team4u-lease`, `team4u-retry`, and `team4u-serializer` no longer exist. Replace each grouping dependency with the concrete artifact that provides the classes you use, managed by the root BOM.

## Wildcard matcher transition

Criterion's `like` syntax now delegates to `com.team4u.framework.base.pattern.PathPatternMatcher` with Team4u Ant-style semantics; the module no longer has a Spring production dependency. Observable behavior is unchanged across the locked 53-case matrix: `*` stays within one `/`-delimited segment, `?` matches one non-separator character, only an exact `**` segment crosses directories, `***` remains segment-local, and a backslash is an ordinary literal. Null behavior is adapter-owned: null pattern/null actual is true, null pattern only is false, and non-null pattern/null actual remains false to public Criterion callers.

Criterion's Spring-only `Team4uCriterionAutoConfiguration` was removed. Register `Criteria.global()`, `StandardCriterionParser.global()`, `CompilerRegistry.global()`, or `ValueConverterRegistry.global()` directly in application configuration if those singletons are needed as beans.
