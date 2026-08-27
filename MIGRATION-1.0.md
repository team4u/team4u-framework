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

The set currently covers minimal base, explicit Jackson provider, and JDK interface proxy consumers. `consumer-serializer-api` is a known transition guard: the current no-provider error names only `JsonSerializerPolicy` and omits the Jackson artifact or custom-provider instruction, so run it explicitly with `-Dinvoker.test=consumer-serializer-api` until Task 7. `consumer-config-core` is also explicit: its scalar/binder path runs, but its runtime tree still carries proxy, ByteBuddy, and Jackson; it joins the default gate in Task 9.

The release baseline gate is:

```bash
mvn -Prelease-contracts -DskipTests verify
```

It runs the same three currently green consumers, executes their mains, and validates or records each runtime dependency tree. Its staged selection is deliberately the extension point that Task 7 and Task 18 strengthen without speculative infrastructure.

## Explicit serializer provider choice

Applications using JSON APIs must choose a provider explicitly. Add `com.team4u:team4u-serializer-jackson` to the application, or provide/register a custom `JsonSerializerPolicy`. Depending only on `team4u-serializer-json` is supported, but JSON calls fail fast with an `IllegalStateException`; Task 7 updates that message to state both choices explicitly.

After importing it, depend on concrete Team4u artifacts without versions.

## Removed grouping artifacts

The pure grouping artifacts `team4u-config`, `team4u-kv`, `team4u-lease`, `team4u-retry`, and `team4u-serializer` no longer exist. Replace each grouping dependency with the concrete artifact that provides the classes you use, managed by the root BOM.
