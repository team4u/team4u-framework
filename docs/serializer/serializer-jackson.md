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
2. **未知属性容错 (`FAIL_ON_UNKNOWN_PROPERTIES = false`)**：
   当服务间契约发生向前演进时，下发的新增字段不会导致旧版本消费方的反序列化失败。
3. **空值精简 (`Include.NON_NULL`)**：
   序列化生成的 JSON 报文中不包含值为 `null` 的键，大幅减少网络传输 IO 与日志存储成本。
4. **环境自检支持 (`supports`)**：
   在 `supports(Void context)` 方法中自动检测类路径下是否存在 `com.fasterxml.jackson.databind.ObjectMapper`。
5. **高优先级生效 (`priority()`)**：
   返回 `ContextPolicy.HIGH`，并在策略链中以 `key = "jackson"` 标识。
