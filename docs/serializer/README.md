# 序列化组件 (team4u-serializer)

# 背景

在微服务 RPC 调用、Redis / KV 缓存存储、事件消息队列以及配置持久化中，序列化与反序列化是不可或缺的基础设施。然而直接在业务中硬编码第三方工具类常常带来以下问题：

- **组件库耦合**：业务代码直接依赖特定的 Jackson、Fastjson 或 Gson API，更换或升级底层库时需要大面积重构；
- **配置割裂**：不同业务模块各自定义 `ObjectMapper`，日期格式、空值忽略与命名策略不一致；
- **缺乏 SPI 扩展机制**：难以向全局序列化器动态注入自定义类型 Module（如 JSR-310 日期模块、Guava 集合支持）。

`team4u-serializer` 提供了统一的抽象序列化门面 `JsonUtil`，基于标准 Java SPI 支持动态接入高性能序列化引擎，并通过 `JacksonModuleContributor` 支持无侵入的模块注册扩展。

---

# 核心特性

- **统一门面 API**：`JsonUtil` 提供最简洁的 `toJson`、`fromJson`、`toJsonBytes` 与 `TypeReference` 泛型反序列化；
- **SPI 引擎解耦**：通过 `JsonSerializerPolicy` SPI 抽象底层序列化实现，无缝适配 Jackson 等现代引擎；
- **`JacksonModuleContributor` SPI**：第三方模块可通过 SPI 动态向全局 `ObjectMapper` 贡献自定义 Module；
- **开箱即用最佳实践**：默认预置 JSR-310 时间支持、忽略未知字段、Map 键有序输出（保证确定性散列）。

---

## 模块坐标

```xml
<dependencies>
    <!-- 核心门面与 SPI 契约 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-serializer-json</artifactId>
    </dependency>

    <!-- Jackson 核心适配实现 (推荐引入) -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-serializer-json-jackson</artifactId>
    </dependency>
</dependencies>
```

---

## 章节导航与专题专栏

- [快速开始](quick-start.md)：5 分钟掌握对象序列化与泛型反序列化。
- [JsonUtil 统一门面与操作](serializer-facade.md)：`JsonUtil` 门面常用方法与类型推导。
- [Jackson 序列化策略与配置](serializer-jackson.md)：全局 ObjectMapper 基础配置与特性说明。
- [自定义 SPI 策略扩展](serializer-spi.md)：实现 `JsonSerializerPolicy` 接入自定义序列化引擎。
- [JacksonModuleContributor SPI](serializer-custom.md)：通过 SPI 动态注册自定义 Jackson 模块。
- [序列化避坑与诊断手册](serializer-diagnostics.md)：泛型擦除、确定性 Map 输出与常见异常自查。
- [序列化组件实战案例](serializer-sample.md)：复杂领域模型、多层集合与通用报文转换实战。
