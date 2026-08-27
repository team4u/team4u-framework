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
```

After importing it, depend on concrete Team4u artifacts without versions.

## Removed grouping artifacts

The pure grouping artifacts `team4u-config`, `team4u-kv`, `team4u-lease`, `team4u-retry`, and `team4u-serializer` no longer exist. Replace each grouping dependency with the concrete artifact that provides the classes you use, managed by the root BOM.
