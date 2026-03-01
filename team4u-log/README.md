# 日志模块

## 模块简介

`team4u-log` 是 Team4u 框架下的高性能、结构化、动态化日志治理模块。它不仅提供了链式的日志打印 API，还内置了自动日志追踪代理、极速敏感数据脱敏、动态靶向染色以及防雪崩限流保护等企业级特性。

模块底层基于策略流水线设计，无缝对接 Jackson 序列化与 SLF4J 门面，并支持通过配置中心（team4u-config）进行规则的实时热重载。

### 核心特性
*   结构化 Fluent API：告别繁琐的字符串拼接，原生输出标准化的 JSON 结构日志。
*   无侵入自动追踪：基于动态代理，只需一个注解即可自动记录方法入参、出参、耗时及异常。
*   极致脱敏性能：基于 Jackson 底层序列化修饰器与直接字符串操作，彻底规避正则开销，支持对象、第三方类库及 Map 嵌套脱敏。
*   动态治理：支持运行时热重载脱敏规则、按条件提权日志级别（染色）、超长日志截断及异常风暴限流。



## 快速入门

### 引入依赖
确保在项目中引入了 `team4u-log` 及其相关核心依赖（继承自 framework 父工程）：
```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-log</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 模块自举（初始化启动）
为了启用动态脱敏、动态染色等高级功能，需在应用启动阶段进行模块初始化：
```java
import com.team4u.log.LogBootstrap;
import com.team4u.framework.config.core.ConfigManager;

// 在应用启动时执行，传入全局配置管理器实例
LogBootstrap.start(globalConfigManager);
```

### 基础日志打印
使用 `Loggers` 提供的 Fluent API 记录业务日志：
```java
import com.team4u.log.Loggers;
import org.slf4j.MDC;

MDC.put("traceId", "tid-998877"); // 模块会自动提取 traceId

UserReq user = new UserReq("周杰伦", "13800138000");

Loggers.of(OrderService.class)
       .action("CreateOrder")        // 业务动作
       .duration(120)                // 耗时(ms)
       .kv("orderId", "ORD-12345")   // 附加 K-V 载荷
       .kv("user", user)
       .success()                    // 标记为成功 (默认 INFO 级别)
       .log();                       // 提交输出
```
输出结果示例:
```json
{"loggerName":"com.demo.OrderService","level":"INFO","traceId":"tid-998877","action":"CreateOrder","status":"success","durationMs":120,"payload":{"orderId":"ORD-12345","user":{"name":"周*伦","phone":"1388000"}},"suppressed":false}
```



## 核心功能详解

### 链式日志构建 API (Fluent API)
`Loggers` 类是进行手动日志记录的核心入口。

| 方法 | 说明 |
| :--- | :--- |
| `of(Class<?> clazz)` | 创建指定类的 Logger 构建器。 |
| `action(String action)` | 设置业务动作名称（必填推荐）。 |
| `kv(String key, Object val)` | 存入业务载荷，支持任意复杂对象，最终会被序列化进 `payload` 字段。 |
| `duration(long ms)` | 设置执行耗时。 |
| `success()` | 快捷方法：状态置为 `success`，级别设为 `INFO`。 |
| `failed(Throwable e)` | 快捷方法：状态置为 `failed`，绑定异常，级别设为 `ERROR`。 |
| `atWarn() / atError()` | 手动指定日志输出级别。 |
| `status(String status)` | 手动指定业务状态（如 `processing`）。 |
| `log()` | 终结方法，将事件提交给日志引擎的流水线进行拦截与输出。 |

*注意：MDC 中的 `traceId` 键值会被 `MdcEnrichInterceptor` 拦截器自动提取并放入最外层结构中。*

#### 日志标准字段说明
采用结构化日志后，为了方便接入 ELK 或类似系统，下表列出了 `LogEvent` 序列化后的核心字段及其含义：

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `loggerName` | String | 日志触发的类名。 |
| `level` | String | 日志级别（INFO/WARN/ERROR/DEBUG/TRACE）。 |
| `traceId` | String | 链路追踪 ID（由拦截器从 MDC 自动提取）。 |
| `action` | String | 业务动作标识（如 `CreateOrder`、`RegisterUser`）。 |
| `status` | String | 业务状态：`success`, `failed`, `processing`, `slow_success`, `business_error`。 |
| `durationMs` | Long | 执行耗时（毫秒），未记录时默认为 `-1`。 |
| `payload` | Object | 业务载荷，包含通过 `kv()` 传入的动态业务数据、脱敏后的 DTO 等。 |
| `dyeingRuleMatched` | String | 仅在命中染色规则时出现，记录命中的规则 ID，方便追溯提权原因。 |



### 自动日志追踪代理 (@AutoLogTrace)
通过为目标类生成动态代理，可以自动记录方法调用的全过程。

*   标记目标方法或类：
```java
public class UserService {
    
    // 简洁模式：默认 action 为方法名 "register"
    @AutoLogTrace
    public String register(UserReq req) {
        // ...
        return "SUCCESS";
    }

    // 自定义模式
    @AutoLogTrace(
        action = "UserLogin", // 自定义动作名称
        slowThreshold = 200,   // 超过200ms的请求自动升级为 WARN 级别
        ignoreExceptions = {BusinessException.class} // 业务异常降级为 WARN，不触发 ERROR 告警
    )
    public void login(String username) {
        // ...
    }
}

// 类级别应用：该类所有公共方法都会被拦截，且默认 action 为方法名
@AutoLogTrace(slowThreshold = 500) 
public class OrderService {
    public void create(OrderReq req) { /* 自动追踪，action="create" */ }
    public void cancel(String id) { /* 自动追踪，action="cancel" */ }
}
```

*   创建代理对象：
```java
// 使用工厂创建代理（简洁模式：自动识别类型）
UserService service = LogProxyFactory.createProxy(new UserService());

// 调用时自动打印入参、出参、耗时及异常
service.register(req); 
```

#### 配置项说明
| 属性 | 类型 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `action` | String | `""` | 业务动作标识。若不填，则**自动取当前方法名**。 |
| `slowThreshold` | long | `-1` | 慢日志阈值(ms)。超过此值时，日志级别自动提升为 `WARN`。 |
| `ignoreExceptions` | Class[] | `{}` | 忽略的异常列表。命中时日志级别降为 `WARN`（用于过滤业务类异常）。 |
| `reqLimit` | int | `2000` | 入参最大打印长度，超长会被截断。 |
| `respLimit` | int | `2000` | 出参最大打印长度。 |

#### 参数名捕获与脱敏技巧
本模块支持自动捕获方法的**参数名称**（如 `req`、`mobile`），并以 Map 结构记录在日志的 `req` 字段中。

**优势：**
*   **结构清晰**：日志从 `["val1", "val2"]` 变为 `{"paramName": "val1", "arg1": "val2"}`。
*   **支持平铺参数脱敏**：即便入参不是 DTO 而是基础类型（如 `String mobile`），只要参数名命中脱敏规则，即可自动掩码。

**最佳实践：**
建议在项目的 `pom.xml` 中开启 `-parameters` 编译参数，以获取真实的参数名而非 `arg0`：
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <arg>-parameters</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

#### 异常处理策略解析
`LogTraceInterceptor` 对异常做了精细化处理，这直接关系到报警策略的配置：
*   未命中 `ignoreExceptions` 的未知异常（如 `NullPointerException`、`SQLException`）：
    *   状态 (`status`)：被标记为 `failed`。
    *   级别 (`level`)：自动标记为 `ERROR`。
    *   建议：此类日志通常需要对接监控系统触发严重告警。
*   命中 `ignoreExceptions` 的业务异常（如 `BalanceNotEnoughException`）：
    *   状态 (`status`)：被标记为 `business_error`。
    *   级别 (`level`)：自动降级为 `WARN`。
    *   好处：有效减少不必要的群级告警风暴，避免运维人员产生“狼来了”的疲劳。
*   超过 `slowThreshold` 的慢日志：
    *   状态 (`status`)：被标记为 `slow_success`。
    *   级别 (`level`)：自动降级为 `WARN`。

### 高性能敏感数据脱敏
引擎内置了基于 Jackson 序列化的极速脱敏机制，无需正则匹配。内置的脱敏类型包括：姓名、手机号、身份证、密码。

#### 注解脱敏（适用于可修改的 DTO）
直接在字段上打上 `@Mask` 注解即可：
```java
public class UserReq {
    @Mask(MaskType.NAME)
    private String name;     // 周杰伦 -> 周*伦

    @Mask(MaskType.PHONE)
    private String phone;    // 13812345678 -> 1385678
}
```

#### 外部动态脱敏（Map、参数名或第三方 DTO）

当无法通过 `@Mask` 注解标记源码时（例如使用 `Map` 存放数据，或调用外部 jar 包中的方法），可以通过配置中心下发脱敏规则。

##### 基于名称的精确匹配
系统会根据对象的**全限定类名**（或通配符 `"*"`）和**字段名/Map Key/方法参数名**去匹配规则。

**脱敏方法参数示例：**
若方法定义为 `send(String mobile, String appSecret)`，开启 `-parameters` 后，可通过以下配置脱敏：

```json
{
  "maskRules": {
    "*": {
      "mobile": "PHONE",
      "appSecret": "PASSWORD"
    }
  }
}
```

##### 默认开箱即用
框架默认对 `java.util.HashMap` 和 `java.util.LinkedHashMap` 的 `"password"` 和 `"creditCard"` 字段开启了脱敏。由于 `.kv()` 和方法入参记录底层均使用 `LinkedHashMap`，这些规则会自动覆盖到 Fluent API 和方法追踪中。

#### 全局通配符脱敏（一劳永逸）
在微服务场景中，DTO 可能多达上千个。本模块支持使用特殊的类名 `"*"` 来定义**全局脱敏规则**。

*   **匹配优先级**：优先进行“类名精确匹配”；若未命中，则回退到 `"*"` 进行“全局字段匹配”。
*   **治理效果**：只要配置了通配符规则，全系统内（包括所有 DTO、第三方类、任意类型的 Map）只要字段名匹配，就会自动触发脱敏。

动态配置示例：
```json
{
  "maskRules": {
    "*": {
      "mobile": "PHONE",
      "password": "PASSWORD"
    }
  }
}
```
## 单元测试与日志验证

得益于结构化的设计，本模块具有极佳的可测试性。框架内置了 `TestLogHelper` 工具类，您可以在单元测试中轻松捕获并断言日志内容。

以下是单元测试中的推荐做法：

```java
import com.team4u.log.support.TestLogHelper;

// 开启日志捕获
TestLogHelper helper = TestLogHelper.start();

try {
    userService.register(new UserReq("周杰伦", "13800138000"));
    
    // 获取最近的一条日志并进行断言
    LogEvent event = helper.lastEvent();
    Assert.assertEquals("RegisterUser", event.getAction());
    Assert.assertEquals("success", event.getStatus());
    
    // 验证脱敏后的 JSON 输出是否符合预期
    String json = helper.lastJson();
    Assert.assertTrue(json.contains("周*伦"));
    
} finally {
    // 停止捕获并重置环境
    helper.stop();
}
```

## 高阶特性

本模块所有的动态治理功能都通过 `team4u-config` 进行热重载，配置键为：`team4u.log.config`。

### 第三方实例的无侵入动态代理追踪

在接入第三方 SDK（如阿里云 OSS 客户端、微信支付 SDK 等）时，我们不仅无法修改其源码添加 `@AutoLogTrace` 注解，甚至连它什么时候发起调用都难以掌控。

为了实现对第三方组件调用的全面可观测性（自动记录入参、出参、耗时）并保证敏感数据（如 SecretKey）的安全脱敏，框架提供了“无侵入动态日志代理”方案。

#### 1. 业务场景演示

假设我们引入了一个第三方的短信发送客户端 `SmsClient`，我们需要记录它的发送日志，同时脱敏其中的手机号和凭证。

```java
// 第三方类库，无法修改源码，无法加注解
public class ThirdPartySmsClient {
    public SmsResponse send(String mobile, String appSecret, String content) {
        // ... 发送逻辑
        return new SmsResponse("OK");
    }
}
```

#### 2. 配置驱动规则 (配置中心)

我们不需要写任何 AOP 代码，只需向配置中心（`team4u.log.config`）推送动态规则：

```json
{
  "proxyRules": {
    "com.thirdparty.ThirdPartySmsClient": {
      "methods": ["send", "queryBalance"],  // 指定要自动打印日志的方法名（支持 "*" 全拦截）
      "slowThreshold": 500                  // 超过 500ms 自动标记为慢日志 (WARN)
    }
  },
  "maskRules": {
    "*": {
      "mobile": "PHONE",         // 入参如果有同名字段，自动脱敏
      "appSecret": "PASSWORD"    // 凭证脱敏为 
    }
  }
}
```

#### 3. 创建动态代理并使用

在将第三方客户端注入到 Spring 容器（或业务代码）之前，使用 `LogProxyFactory` 对其进行包装：

```java
// 1. 原始第三方实例
ThirdPartySmsClient rawClient = new ThirdPartySmsClient();

// 2. 一行代码生成动态代理（底层基于 team4u-proxy 的 ByteBuddy 引擎）
ThirdPartySmsClient safeClient = LogProxyFactory.createDynamicProxy(rawClient, ThirdPartySmsClient.class);

// 3. 业务方毫无感知地调用代理对象
safeClient.send("13812345678", "sk_live_123abc", "您的验证码是 9527");
```

#### 4. 自动生成的 JSON 日志结果

当代理方法被执行时，框架会自动拦截并输出极其规范的结构化日志，同时完成了极速脱敏：

```json
{
  "loggerName": "com.thirdparty.ThirdPartySmsClient",
  "level": "INFO",
  "action": "send",
  "status": "success",
  "durationMs": 12,
  "payload": {
    "req": [
      "1385678",    // <-- 手机号被自动脱敏
      "",         // <-- 凭证被自动脱敏
      "您的验证码是 9527"
    ],
    "resp": {
      "status": "OK"
    }
  }
}
```

### 动态靶向染色
在排查线上问题时，可能需要将特定用户（如白名单用户）或特定接口的日志级别从 `INFO` / `TRACE` 动态提升到 `DEBUG`。

依赖的上下文变量： 载荷 `payload` 中的属性、`action`、`level` 以及 MDC 中的 `X-User-Id`。

动态配置示例：
```json
{
  "dyeingRules": [
    {
      "id": "vip_user_debug",
      "condition": "(action == 'Pay' || userId == '10086') && level == 'ERROR'",
      "targetLevel": "DEBUG"
    }
  ]
}
```
*效果：一旦条件匹配（利用 `team4u-criterion` 表达式引擎），日志级别将被自动篡改，并在 Payload 中打上标记 `"dyeingRuleMatched": "vip_user_debug"`。*

### 防雪崩限流与长度截断
为了防止底层数据库宕机导致的异常日志风暴（拖垮磁盘 IO 和日志采集系统）以及超大报文引起的 OOM，模块内置了双重保护机制。

*   雪崩限流：针对同一种类异常（特征：`Action` + `ExceptionClassName`），默认限制 10条 / 秒。超出部分的日志将被静默丢弃，系统仅输出一条简短的超限告警。
*   字段级截断（Value-Level）：在 Jackson 序列化阶段，单个 `String` 字段若超过 `maxStringLength`（默认 2000），将直接被截断并追加 `... [Truncated len:xxx]`。这能有效防止大报文在序列化过程中消耗过多内存和 CPU。
*   字节数组防御：为了防止将大文件（`byte[]`）序列化为巨大的 Base64 字符串，系统会拦截所有 `byte[]` 类型的输出，统一替换为大小提示（如 `[byte[] size: 1024 bytes]`）。
*   日志级截断（Log-Level）：单条 JSON 日志序列化后的字符串整体默认最大长度为 `maxLogLength`（默认 5000）。超过部分将被截断并追加 `... [Truncated at 5000]`。

动态调整阈值：
```json
{
  "finOpsConfig": {
    "maxLogLength": 5000,
    "maxStringLength": 2000,
    "errorLimitPerSecond": 10
  }
}
```

### 配置中心动态热重载
完整的 `team4u.log.config` JSON 配置示例，当你向配置中心推送此 JSON 时，系统会自动热生效所有策略：

```json
{
  "maskRules": {
    "com.demo.ThirdPartyUser": {
      "mobile": "PHONE"
    },
    "java.util.HashMap": {
      "mobile": "PHONE",
      "creditCard": "DYNAMIC"
    }
  },
  "proxyRules": {
    "com.thirdparty.ThirdPartySmsClient": {
      "methods": ["*"],
      "slowThreshold": 200
    }
  },
  "dyeingRules": [
    {
      "id": "trace_to_info",
      "condition": "action == 'CreateOrder'",
      "targetLevel": "INFO"
    }
  ],
  "finOpsConfig": {
    "maxLogLength": 5000,
    "maxStringLength": 2000,
    "errorLimitPerSecond": 10
  }
}
```



## 自定义扩展与底层配置注意事项

### MDC 上下文隐式规则
特别指出模块不仅提取 `traceId`，在执行“动态染色”匹配时，还会默认提取 `MDC.get("X-User-Id")` 作为上下文变量 `userId`。如果开发者在网关层统一塞入该值，可配合染色规则大幅提升排障效率。

### 自定义 Appender
框架允许越过 SLF4J，直接将结构化对象推送到远程系统（如直接发送至 Kafka/Elasticsearch）。

```java
// 注册自定义 Appender
LogEngine.getInstance().setAppender(new LogAppender() {
    @Override
    public void append(LogEvent event) {
        String json = LogEngine.getInstance().toJson(event);
        // 执行发送逻辑，例如：kafkaTemplate.send("log_topic", json);
    }
});
```

### Logback/Log4j2 配合说明
由于本模块已经将日志格式化为了标准的 JSON 字符串，建议底层的 `logback.xml` 或 `log4j2.xml` 配置只需最简的 `%msg%n` 即可。不要再让底层的日志框架执行额外的格式化或再次 JSON 化，以避免性能损失和结构冗余。



## 架构与执行流程参考

当调用 `log()` 方法时，日志事件 (`LogEvent`) 将经历如下流水线处理：

*   MdcEnrichInterceptor (最高优先级)：从 SLF4J 提取 `traceId` 注入到上下文中。
*   TargetedDyeingInterceptor (普通优先级)：判断条件，如果命中则修改 Level 提权/降权。
*   RateLimitInterceptor (低优先级)：针对携带 Exception 的日志进行签名（action+ExceptionClass），计算 1 秒内频次，超限则中断流水线。
*   序列化与脱敏：由 `LogEngine` 调用定制后的 `ObjectMapper` 进行 JSON 化。脱敏修饰器 (`DynamicMaskSerializerModifier`) 会在此时拦截注解和配置，执行 `FastMasker` 极速脱敏。
*   输出 (Appender)：校验长度是否超过 `maxLogLength` 并截断，最后交给 `Slf4jLogAppender` 打印输出。