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

The set currently covers minimal base, explicit Jackson provider, and JDK interface proxy consumers. `consumer-serializer-api` is a known transition guard: the current no-provider error names only `JsonSerializerPolicy` and omits the Jackson artifact or custom-provider instruction, so run it explicitly with `-Dinvoker.test=consumer-serializer-api` until Task 7. `consumer-config-core` is also explicit: its scalar/binder path runs, but its runtime tree still carries proxy and Jackson; it joins the default gate in Task 9.

The release baseline gate is:

```bash
mvn -Prelease-contracts -DskipTests verify
```

It runs the same three currently green consumers, executes their mains, and validates or records each runtime dependency tree. Its staged selection is deliberately the extension point that Task 7 and Task 18 strengthen without speculative infrastructure.

## Explicit serializer provider choice

Applications using JSON APIs must choose a provider explicitly. Add `com.team4u:team4u-serializer-jackson` to the application, or provide/register a custom `JsonSerializerPolicy`. Depending only on `team4u-serializer-json` is supported, but JSON calls fail fast with an `IllegalStateException`; Task 7 updates that message to state both choices explicitly.

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

The same rule applies to concrete-class proxy paths in `team4u-config-core`, `team4u-log`, and `team4u-retry-proxy`. Their interface proxy paths do not require ByteBuddy.

The engine is attempted from the thread context loader, the target type's loader, and then `ProxyBuilder`'s defining loader. Child-first/plugin loaders that can define both the engine and ByteBuddy are supported. A JVM visibility boundary remains if `ProxyBuilder` is parent-defined, the engine is ordinary parent-delegated, and only a normal child loader carries ByteBuddy: a parent-defined engine class cannot resolve types visible only to that child. Place ByteBuddy in the parent visible to the engine, or use a loader that defines both.

## Removed grouping artifacts

The pure grouping artifacts `team4u-config`, `team4u-kv`, `team4u-lease`, `team4u-retry`, and `team4u-serializer` no longer exist. Replace each grouping dependency with the concrete artifact that provides the classes you use, managed by the root BOM.

## Wildcard matcher transition

Criterion's `like` syntax now delegates to `com.team4u.framework.base.pattern.PathPatternMatcher` with Team4u Ant-style semantics; the module no longer has a Spring production dependency. Observable behavior is unchanged across the locked 53-case matrix: `*` stays within one `/`-delimited segment, `?` matches one non-separator character, only an exact `**` segment crosses directories, `***` remains segment-local, and a backslash is an ordinary literal. Null behavior is adapter-owned: null pattern/null actual is true, null pattern only is false, and non-null pattern/null actual remains false to public Criterion callers.

Criterion's Spring-only `Team4uCriterionAutoConfiguration` was removed. Register `Criteria.global()`, `StandardCriterionParser.global()`, `CompilerRegistry.global()`, or `ValueConverterRegistry.global()` directly in application configuration if those singletons are needed as beans.
