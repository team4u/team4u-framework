# 字典强类型读取器 (MapReader)

在现代应用开发中，程序经常需要从弱类型、动态结构的字典（如 JSON 反序列化产物、YAML 配置、HTTP 请求参数、SPI 扩展参数或上下文环境变量）中读取参数。

传统方式通常面临以下痛点：
- **大量类型强转样板**：频繁编写 `(Integer) map.get("port")` 容易引发 `ClassCastException` 或空指针异常；
- **大小写与命名风格不一致**：配置中心可能传入 `maxAttempts`（camelCase）、`max-attempts`（kebab-case）或 `max_attempts`（snake_case），手写多分支判定繁琐易错；
- **复杂类型解析困难**：解析时长（如 `"100ms"`, `"3s"`）、枚举或嵌套对象时需大量自定义解析逻辑。

`team4u-base` 提供了 [`MapReader`](file:///root/code/team4u-framework/modules/base/core/src/main/java/com/team4u/framework/base/util/MapReader.java) 与 [`MapUtil.reader`](file:///root/code/team4u-framework/modules/base/core/src/main/java/com/team4u/framework/base/util/MapUtil.java)，基于 [`ConvertUtil`](file:///root/code/team4u-framework/modules/base/core/src/main/java/com/team4u/framework/base/convert/ConvertUtil.java) 提供强类型安全、多键别名回退与安全默认值的流式参数提取能力。

---

## 核心特性

- **多 Key 别名回退（Alias Fallback）**：支持传入多个候选 Key（如 `maxAttempts`, `max-attempts`, `max_attempts`），按顺序查找首个非 null 值；
- **全类型安全提取**：内置 `getString`, `getInt`, `getLong`, `getDouble`, `getBoolean`, `getDuration`, `getEnum`, `get(Class<T>)` 等常用类型读取；
- **POJO 对象转换 (toBean)** ：内置支持将 MapReader 转换为任意强类型 JavaBean，默认自动兼容 kebab-case、snake_case 等命名风格并忽略转换错误，亦支持自定义 CopyOptions；
- **时长原生解析**：支持文本格式时长（`100ms`, `5s`, `10m`, `1h`, `2d`）、纯数字毫秒数及 ISO-8601 格式（`PT10S`）；
- **必填项严格断言**：提供 `require` 与 `requireString`，缺失时自动抛出清晰的 `IllegalArgumentException`；
- **Null 安全与优雅默认值**：所有方法均支持传入默认值，当 Map 为 null 或未匹配时安全回退。

---

## API 清单

### 创建实例

```java
// 通过工厂方法创建
MapReader reader = MapReader.of(map);

// 或通过 MapUtil 便捷入口创建
MapReader reader = MapUtil.reader(map);
```

### 核心方法矩阵

| 方法签名 | 说明 |
| :--- | :--- |
| `Object getRaw(String key, String... aliases)` | 按主键及别名依次查找首个非 null 原始值 |
| `Object require(String key, String errorMessage, String... aliases)` | 读取必填原始参数，缺失时抛出异常 |
| `String requireString(String key, String errorMessage, String... aliases)` | 读取必填字符串参数，缺失时抛出异常 |
| `boolean containsKey(String key, String... aliases)` | 检查是否包含主键或任一别名 |
| `String getString(String key, [String defaultValue], String... aliases)` | 读取字符串参数 |
| `Integer getInt(String key, [Integer defaultValue], String... aliases)` | 读取整型参数 |
| `Long getLong(String key, [Long defaultValue], String... aliases)` | 读取长整型参数 |
| `Double getDouble(String key, [Double defaultValue], String... aliases)` | 读取浮点数参数 |
| `Boolean getBoolean(String key, [Boolean defaultValue], String... aliases)` | 读取布尔参数（支持 true/1/yes/on/y 等） |
| `Duration getDuration(String key, [Duration defaultValue], String... aliases)` | 读取时长参数（支持 100ms/5s/10m/1h/2d/PT10S） |
| `<E extends Enum<E>> E getEnum(Class<E> enumClass, String key, [E defaultValue], String... aliases)` | 读取枚举参数（大小写不敏感匹配） |
| `<T> T get(Class<T> type, String key, [T defaultValue], String... aliases)` | 通用类型安全转换读取 |
| `<T> T toBean(Class<T> beanClass)` | 将字典转换为指定类型的 Bean 对象（默认忽略大小写/下划线/中划线与错误） |
| `<T> T toBean(Class<T> beanClass, CopyOptions options)` | 按指定的拷贝选项将字典转换为 Bean 对象（字典为空或 Class 为 null 返回 null） |
| `MapReader getReader(String key, String... aliases)` | 读取嵌套子字典读取器（永不为 null） |
| `Map<?, ?> toMap()` | 获取底层原始字典对象（永不为 null） |
| `boolean isEmpty()` | 判断底层字典是否为空 |
| `int size()` | 获取底层字典条目数 |

---

## 典型使用示例

### 配置字典解析与别名回退

```java
import com.team4u.framework.base.util.MapReader;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class ConfigReaderExample {

    public static void main(String[] args) {
        Map<String, Object> config = new HashMap<>();
        config.put("server-port", "8080");
        config.put("retry_timeout", "500ms");
        config.put("log-level", "debug");
        config.put("enabled", "true");

        MapReader reader = MapReader.of(config);

        // 1. 别名回退读取端口：优先找 serverPort，回退找 server-port
        int port = reader.getInt("serverPort", 80, "server-port"); // 8080

        // 2. 时长解析：将 "500ms" 转换为 Duration
        Duration timeout = reader.getDuration("retryTimeout", Duration.ofSeconds(1), "retry_timeout"); // PT0.5S

        // 3. 枚举大小写不敏感解析
        LogLevel level = reader.getEnum(LogLevel.class, "logLevel", LogLevel.INFO, "log-level"); // LogLevel.DEBUG

        // 4. 布尔值解析
        boolean enabled = reader.getBoolean("enabled", false); // true

        // 5. 必填项校验
        String serverName = reader.requireString("serverName", "服务名称不能为空", "server-name");
    }

    enum LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
}
```

### 嵌套子字典流式导航

```java
MapReader root = MapReader.of(nestedMap);

// 链式导航进入子字典并提取强类型参数
int redisPort = root.getReader("spring")
                    .getReader("redis")
                    .getInt("port", 6379);
```

### 字典转换为 POJO 对象 (toBean)

支持一键将当前 `MapReader` 或嵌套子读取器转换为强类型 JavaBean，自动兼容 `kebab-case`、`snake_case` 等命名风格：

```java
public class ServerProperties {
    private String serverName;
    private int serverPort;
    private Duration timeout;
    private SubProperties sub;

    // getters and setters
}

public class SubProperties {
    private String host;
    private int port;

    // getters and setters
}

// 1. 默认宽松安全转换（自动映射 server-name -> serverName, timeout: "10s" -> Duration）
ServerProperties server = reader.toBean(ServerProperties.class);

// 2. 链式导航转换子对象
SubProperties sub = reader.getReader("sub").toBean(SubProperties.class);

// 3. 自定义严格拷贝策略（区分大小写与抛出转换异常）
CopyOptions strictOptions = CopyOptions.create();
ServerProperties strictServer = reader.toBean(ServerProperties.class, strictOptions);
```

### 配置中心原生整合

`team4u-config` 中的 `ConfigManager` 与 `ConfigSnapshot` 原生整合 `MapReader`，支持按前缀直接进行流式强类型读取：

```java
// 直接提取特定前缀的强类型子树读取器
MapReader redis = ConfigManager.global().asReader("spring.redis");
String host = redis.getString("host", "127.0.0.1");
int port = redis.getInt("port", 6379);
Duration timeout = redis.getDuration("timeout", Duration.ofSeconds(3));
```

---

## 关联章节与进一步阅读

- [类型转换器体系 (ConvertUtil)](base-convert.md)
- [快速开始](quick-start.md)
- [核心基础组件概览 (README.md)](README.md)
