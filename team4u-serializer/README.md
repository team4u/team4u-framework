# team4u-serializer

## 为什么需要它

在分布式系统和微服务架构中，JSON 序列化是最基础且频繁使用的功能。然而，直接依赖具体的序列化库（如 Jackson、Gson 或 Fastjson）会导致业务代码与特定框架深度耦合，带来以下问题：

- 框架锁定：难以在不修改业务代码的情况下更换底层序列化引擎。
- 版本冲突：不同模块依赖不同版本的序列化库时，容易引发类冲突或行为不一致。
- 配置散乱：各处散落的序列化配置（如日期格式、空值处理）难以统一管理。

`team4u-serializer` 通过抽象的序列化策略接口与自动发现机制，提供了一个轻量级、可扩展且框架无关的序列化解决方案。

## 核心概念

### JsonSerializerPolicy (序列化策略)

这是核心的 SPI 接口，定义了统一的序列化与反序列化行为。通过实现此接口，可以将任何第三方序列化库集成到框架中。

核心职责：
- `toJsonStr`：将对象转换为标准 JSON 字符串。
- `toBean`：支持简单类及复杂泛型（Type/TypeReference）的反序列化。
- `toList`：便捷地将 JSON 数组转换为对象列表。
- `parseObj`：解析为通用对象（如底层框架的树状节点）。

### JsonUtil (核心门面)

业务侧直接使用的工具类，通过内部的 `OrderedPolicyChain` 在运行时自动扫描并选择优先级最高且支持当前环境的策略实现。

这种机制保证了：
1. **零配置接入**：只要类路径下存在有效的策略实现（如 `team4u-serializer-jackson`），即可直接使用。
2. **灵活扩展**：业务方可以通过实现自己的策略并标记更高的优先级来覆盖默认行为。

## 快速开始

### 1. 引入依赖

推荐使用基于 Jackson 的实现方案：

```xml
<dependency>
    <groupId>io.github.jayblue98</groupId>
    <artifactId>team4u-serializer-jackson</artifactId>
    <version>${version}</version>
</dependency>
```

如果你只需要核心接口（例如在开发通用中间件时）：

```xml
<dependency>
    <groupId>io.github.jayblue98</groupId>
    <artifactId>team4u-serializer-json</artifactId>
    <version>${version}</version>
</dependency>
```

### 2. 基础对象序列化

```java
import com.team4u.framework.serializer.json.JsonUtil;

// 序列化
User user = new User("jay", 18);
String json = JsonUtil.toJsonStr(user);

// 反序列化
User decoded = JsonUtil.toBean(json, User.class);
```

### 3. 处理复杂泛型

对于 `List<Map<String, User>>` 这种复杂结构，请使用 `TypeReference`：

```java
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.serializer.json.TypeReference;

String json = "[{\"name\":\"jay\"}]";
List<User> users = JsonUtil.toBean(json, new TypeReference<List<User>>() {});
```

### 4. 列表便捷转换

```java
import com.team4u.framework.serializer.json.JsonUtil;

List<User> users = JsonUtil.toList(json, User.class);
```

## 模块说明

### `team4u-serializer-json`

核心抽象模块，定义了统一的模型、工具类与策略接口。
- 不依赖任何具体的 JSON 第三方库。
- 提供 `JsonUtil` 门面及 `TypeReference` 泛型支持。

### `team4u-serializer-jackson`

基于 Jackson 实现的高性能序列化模块。
- 默认提供 `JacksonSerializerPolicy` 实现。
- 支持 Java 8 时间 API、常用集合类的序列化配置。
- 适合大多数生产环境。

## 核心模型

### JsonSerializerPolicy 接口

```java
public interface JsonSerializerPolicy {
    // 对象转 JSON 字符串
    String toJsonStr(Object obj);

    // JSON 字符串转对象（Class 方式）
    <T> T toBean(String json, Class<T> clazz);

    // JSON 字符串转复杂对象（Type 方式）
    <T> T toBean(String json, Type type);

    // 解析为底层通用对象
    Object parseObj(String json);
}
```

## 执行保证与语义

### 策略加载语义

系统基于 `PolicyScanner` 机制进行加载：
1. **ServiceLoader 加载**：通过 `META-INF/services` 发现实现类。
2. **注解扫描**：扫描类路径下带有相关注解的策略类。
3. **优先级排序**：根据策略定义的 `order` 值进行排序，数值越小优先级越高。

### 异常处理

- 如果类路径下没有任何策略实现，调用 `JsonUtil` 方法时将抛出 `IllegalStateException`。
- `JsonUtil.toBean(json, typeReference, ignoreError)` 提供了一个可选的忽略错误开关，在解析失败时返回 `null` 而不抛出异常。

## 后端实现细节

### Jackson 实现 (JacksonSerializerPolicy)

- **自动注册**：通过 `META-INF/services` 自动注册到 `JsonUtil`。
- **配置定制**：当前版本采用标准 Jackson 配置，确保了极高的兼容性与性能。
- **空值处理**：默认处理策略保持与底层 Jackson 配置一致。

## 构建与测试

### 执行测试
```bash
mvn test
```

### 测试说明
- 单元测试位于 `team4u-serializer-jackson` 模块中。
- `JsonUtilTest` 涵盖了策略加载验证、基础转换、泛型处理以及通用解析等核心场景。

## 适用与局限

| 推荐场景 | 不太适合 |
| :--- | :--- |
| 需要解耦底层 JSON 框架的业务系统 | 对序列化性能有极致要求（如微秒级）且不介意框架耦合的底层驱动 |
| 需要统一管理序列化行为的中间件 | 需要使用特定厂商非标扩展特性的场景 |
| 需要在不同底层库之间平滑切换的项目 | |
