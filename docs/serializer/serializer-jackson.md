# Jackson 驱动与性能优化

`team4u-serializer-jackson` 是官方提供的基于 Jackson 实现的高性能序列化驱动模块。

---

## 核心设计与配置细节

`JacksonSerializerPolicy` 内部对 `ObjectMapper` 进行了针对微服务场景的最佳实践预设：

```java
package com.team4u.framework.serializer.json.jackson;

public class JacksonSerializerPolicy implements JsonSerializerPolicy {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        // 1. 注册 Java 8 JSR310 时间模块
        OBJECT_MAPPER.registerModule(new JavaTimeModule());

        // 2. 忽略未知的 JSON 属性，防止扩展字段反序列化报错
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 3. 序列化时忽略 null 值字段，缩减传输报文体积
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // 4. 日期时间格式化为标准 ISO 字符串，而非时间戳数字
        OBJECT_MAPPER.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }
}
```

---

## 关键特性一览

1. **JSR310 时间格式支持**：
   原生支持 `java.time.LocalDateTime`、`java.time.LocalDate`、`java.time.Instant` 等类型的标准格式化转换。
2. **未知属性容错** (`FAIL_ON_UNKNOWN_PROPERTIES = false`)：
   当服务间契约发生向前演进时，下发的新增字段不会导致旧版本消费方的反序列化失败。
3. **空值精简** (`Include.NON_NULL`)：
   序列化生成的 JSON 报文中不包含值为 `null` 的键，大幅减少网络传输 IO 与日志存储成本。
4. **环境自检支持** (`supports`)：
   在 `supports(Void context)` 方法中自动检测类路径下是否存在 `com.fasterxml.jackson.databind.ObjectMapper`。
5. **高优先级生效** (`priority()`)：
   返回 `ContextPolicy.HIGH`，并在策略链中以 `key = "jackson" 标识。

---

## 共享 ObjectMapper 与扩展模块注册

`JacksonSerializerPolicy` 是全局共享 `ObjectMapper` 的唯一权威来源——所有走 `JsonUtil` 的 JSON 序列化都经由共享 mapper 执行，各业务模块不得私建 `ObjectMapper`。

> [!WARNING]
> **无损契约**：共享 mapper 永远执行无损序列化——存库、缓存、重放载荷等存储向场景必须拿到原文明文。**会改变输出内容的观测向模块（脱敏、日志截断）严禁注册全局**，必须由调用方在副本/门面上显式叠加（脱敏见 mask 模块的 `MaskedJson` 门面）；全局只接受不改变语义的安全模块（如 `JavaTimeModule`、多态支持等）。扩展模块通过静态 API 或 SPI 注册：

```java
// 静态注册：建议在应用启动阶段（首次 JSON 访问前）调用；
// 若共享 mapper 已初始化，注册同样立即生效
// 例：注册不改变语义的安全模块（如 Kotlin 支持模块）
boolean registered = JacksonSerializerPolicy.registerModule(new KotlinModule.Builder().build());

// SPI 注册：实现 JacksonModuleContributor 并提供服务文件，
// 共享 mapper 首次初始化时自动发现并注册
public final class KotlinSupportContributor implements JacksonModuleContributor {
    @Override
    public Collection<Module> modules() {
        return Collections.singletonList(new KotlinModule.Builder().build());
    }
}
```

要点：

- **幂等**：重复注册同一模块（按「模块实现类 + 模块名」判定）返回 `false`，不会叠加序列化器/修饰器；
- **整体重建**：模块集变化时基于「基础配置 + 全量模块」重建共享 mapper，而非增量 `registerModule`——Jackson 对已序列化过的类型会缓存序列化器，增量注册无法覆盖这些缓存，晚注册的模块对已缓存类型不会生效；
- **只读访问**：`JacksonSerializerPolicy.sharedMapper()` 返回共享实例，仅供读取与序列化操作，**严禁**直接修改其配置（`registerModule` / `configure` / `setXXX`）；注册扩展模块必须走 `registerModule`。注册新模块后，此前拿到的旧引用仍可用（保留旧模块集），需要最新模块集时应重新获取。
