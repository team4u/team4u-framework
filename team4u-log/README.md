# 日志模块

## 模块简介

`team4u-log` 是 Team4u 框架下的高性能、结构化、动态化日志治理模块。它不仅提供了链式的日志打印 API，还集成了自动日志追踪代理、`team4u-mask` 极速脱敏能力、动态靶向染色以及防雪崩限流保护等企业级特性。

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
import com.team4u.framework.log.LogBootstrap;

// 1) 全部使用默认全局实例（推荐开箱即用）
LogBootstrap.start();

// 2) 仅覆盖配置管理器，其余保持默认
LogBootstrap.

start(LogBootstrap.Options.builder()
        .

configManager(globalConfigManager)
        .

build());

// 3) 同时覆盖 Criteria（用于业务方注入自定义规则引擎实例）
        LogBootstrap.

start(LogBootstrap.Options.builder()
        .

configManager(globalConfigManager)
        .

criteria(customCriteria)
        .

build());

// 4) 运行中显式重配
        LogBootstrap.

reconfigure(LogBootstrap.Options.builder()
        .

configManager(anotherConfigManager)
        .

build());

// 5) 应用关闭时释放监听
        LogBootstrap.

stop();
```

> 说明：
> - `LogBootstrap.start()` 使用 `ConfigManager.global()` 和 `Criteria.global()`。
> - `start(options)` 只负责启动；运行中换依赖请使用 `reconfigure(options)`。
> - `stop()` 幂等，可用于测试与嵌入式场景清理。

### 基础日志打印
使用 `Loggers` 提供的 Fluent API 记录业务日志：
```java
import com.team4u.framework.log.Loggers;
import org.slf4j.MDC;

MDC.put("traceId", "tid-998877"); // 模块会自动提取 traceId

UserReq user = new UserReq("周杰伦", "13800138000");

Loggers.of(OrderService.class)
       .action("CreateOrder")        // 业务动作
       .duration(120)                // 耗时(ms)
       .put("orderId", "ORD-12345")   // 附加 K-V 载荷
       .put("user", user)
       .success()                    // 标记为成功 (默认 INFO 级别)
       .log();                       // 提交输出
```
输出结果示例:
```json
{"loggerName":"com.demo.OrderService","level":"INFO","traceId":"tid-998877","action":"CreateOrder","status":"success","durationMs":120,"payload":{"orderId":"ORD-12345","user":{"name":"**伦","phone":"138*****000"}},"suppressed":false}
```



## 核心功能详解

### 链式日志构建 API (Fluent API)
`Loggers` 类是进行手动日志记录的核心入口。

| 方法                                                      | 说明                                                               |
| :-------------------------------------------------------- | :----------------------------------------------------------------- |
| `of(Class<?> clazz)`                                      | 创建指定类的 Logger 构建器。                                       |
| `action(String action)`                                   | 设置业务动作名称（必填推荐）。                                     |
| `put(String key, Object value)`                           | 存入业务载荷，支持任意复杂对象，最终会被序列化进 `payload` 字段。  |
| `putAll(Map<String, Object> map)`                         | 批量存入业务载荷。                                                 |
| `duration(long ms)`                                       | 设置执行耗时。                                                     |
| `success()`                                               | 快捷方法：状态置为 `success`，级别设为 `INFO`。                    |
| `failed(Throwable e)`                                     | 快捷方法：状态置为 `failed`，绑定异常，级别设为 `ERROR`。          |
| `level(Level level)`                                      | 通用方法：直接设置日志级别。                                       |
| `atTrace() / atDebug() / atInfo() / atWarn() / atError()` | 手动指定日志输出级别。                                             |
| `status(String status)`                                   | 手动指定业务状态（如 `processing`）。                              |
| `derive()`                                                | 派生日志器。基于当前状态做浅拷贝并返回副本。常用于定义日志模板。   |
| `begin()`                                                 | 开启一个日志区间（Span），支持自动计时。                           |
| `around(Runnable/Callable)`                               | 便捷方法：包围执行业务逻辑，自动记录开始和结束（含计时）。         |
| `log()`                                                   | 终结方法，将事件提交给日志引擎的流水线进行拦截与输出。             |
### 拦截器管理 (LogInterceptorManager)
日志处理流水线由 `LogInterceptorManager` 统一管理。它负责内置拦截器的初始化、自定义拦截器的注册以及执行链的调度。

#### 内置拦截器
默认情况下，系统会自动注册以下拦截器：
1.  MdcEnrichInterceptor (优先级: HIGH)：从 MDC 中提取 `traceId`。
2.  TargetedDyeingInterceptor (优先级: NORMAL)：执行动态染色规则。
3.  RateLimitInterceptor (优先级: LOW)：执行异常日志限流。

#### 自定义拦截器注册
您可以通过以下几种方式扩展日志处理逻辑：

1. 编程式注册：
```java
LogEngine.getInstance()
         .getInterceptorManager()
         .register(new MyCustomInterceptor());
```

2. SPI 自动发现：
在 `META-INF/services/com.team4u.framework.log.pipeline.LogInterceptor` 文件中填入实现类的全限定名，系统启动时会自动加载并按 `priority()` 排序。

#### 常用拦截器配置
*   自定义从 MDC 中提取的键名：
```java
MdcEnrichInterceptor.getInstance().setTraceIdKey("requestId");
```

---
#### 日志器派生 (Template Logger)
为了减少重复代码（如每个方法都要手动 `.put("module", "Trade")`），您可以预定义一个模板日志器，在具体业务点通过 `derive()` 派生出独立实例。派生实例会继承模板的所有 KV 和配置，且后续对顶层字段与 payload Map 的修改互不污染。

> 注意：
> `derive()` 对 `payload` 只做浅拷贝。也就是说，`payload` 里的嵌套可变对象（如 `List`、`Map`、自定义 DTO）仍然可能与模板共享引用。
> 若模板中放入的是可变对象，后续对这些内部对象的修改仍可能相互影响。建议模板 payload 只放不可变值或每次派生后重新填充可变内容。

```java
public class OrderService {
    // 1. 定义模板：预置公共 KV
    private static final Loggers BASE_LOG = Loggers.of(OrderService.class)
            .put("module", "OrderCenter")
            .put("version", "v2.0");

    public void createOrder(String id) {
        // 2. 派生副本使用：副本上的 action/put 不会影响 BASE_LOG 模板
        BASE_LOG.derive()
                .action("CreateOrder")
                .put("orderId", id)
                .success()
                .log();
    }
}
```

---
#### 区间日志 (Log Span)
除了直接输出结果日志，`Loggers` 还提供 `begin()` 方法开启一个执行区间（Span），并在结束时自动计算耗时。

**主要特性：**
*   **自动计时**：自动记录开始时间，在调用状态方法（如 `success()`, `failed()`）时自动填入 `durationMs`。
*   **状态隔离**：通过 `logStart()` 记录开始日志时，会自动克隆当前上下文，不影响最终结束日志的状态。
*   **包围模式**：提供 `around()` 方法，一键完成“记录开始 -> 执行业务 -> 记录结果”的全流程。

**1. 手动管理 Span：**
```java
LogSpan span = Loggers.of(OrderService.class)
        .action("CreateOrder")
        .put("orderId", orderId)
        .begin()         // 开启 Span 并记录起始时间
        .logStart();     // 可选：立即输出一条 status="start" 的日志

try {
    businessService.doSomething();
    span.success().log(); // 自动计算耗时并输出结果日志
} catch (Exception e) {
    span.failed(e).log(); // 自动计算耗时，记录异常并输出
}
```

**2. 使用 Around 便捷方法：**
```java
// 自动处理异常捕获与耗时计算
Loggers.of(OrderService.class)
       .action("CreateOrder")
       .put("orderId", orderId)
       .around(() -> businessService.doSomething());
```

#### 日志标准字段说明
采用结构化日志后，为了方便接入 ELK 或类似系统，下表列出了 `LogEvent` 序列化后的核心字段及其含义：

| 字段名              | 类型   | 说明                                                                            |
| :------------------ | :----- | :------------------------------------------------------------------------------ |
| `loggerName`        | String | 日志触发的类名。                                                                |
| `level`             | String | 日志级别（INFO/WARN/ERROR/DEBUG/TRACE）。                                       |
| `traceId`           | String | 链路追踪 ID（由拦截器从 MDC 自动提取）。                                        |
| `action`            | String | 业务动作标识（如 `CreateOrder`、`RegisterUser`）。                              |
| `status`            | String | 业务状态：`success`, `failed`, `processing`, `slow_success`, `business_error`。 |
| `durationMs`        | Long   | 执行耗时（毫秒），未记录时默认为 `-1`。                                         |
| `payload`           | Object | 业务载荷，包含通过 `put()` / `putAll()` 传入的动态业务数据、脱敏后的 DTO 等。   |
| `dyeingRuleMatched` | String | 仅在命中染色规则时出现，记录命中的规则 ID，方便追溯提权原因。                   |



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
| 属性               | 类型    | 默认值 | 说明                                                              |
| :----------------- | :------ | :----- | :---------------------------------------------------------------- |
| `action`           | String  | `""`   | 业务动作标识。若不填，则自动取当前方法名。                        |
| `slowThreshold`    | long    | `-1`   | 慢日志阈值(ms)。超过此值时，日志级别自动提升为 `WARN`。           |
| `ignoreExceptions` | Class[] | `{}`   | 忽略的异常列表。命中时日志级别降为 `WARN`（用于过滤业务类异常）。 |

#### 参数名捕获与脱敏技巧
本模块支持自动捕获方法的参数名称（如 `req`、`mobile`），并以 Map 结构记录在日志的 `req` 字段中。

优势：
*   结构清晰：日志从 `["val1", "val2"]` 变为 `{"paramName": "val1", "arg1": "val2"}`。
*   主动脱敏保护：即便入参不是 DTO 而是基础类型（如 `String mobile`），只要参数名命中脱敏规则，拦截器会在序列化之前主动调用 `FastMasker` 进行掩码，确保敏感信息在内存处理阶段即受到保护。

最佳实践：
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
`team4u-log` 不再内置脱敏实现，而是在日志序列化与代理链路中集成 `team4u-mask` 能力。

> 完整脱敏能力（算法清单、注解、SPI、规则仓库）请参考 [`team4u-mask/README.md`](../team4u-mask/README.md)。本章仅说明在日志场景如何接入。

#### 在日志模块中的接入方式

1. **对象字段脱敏（序列化阶段）**
   在 DTO 字段上使用 `@Mask`，日志序列化时自动生效。

   ```java
   public class UserReq {
       @Mask(MaskType.NAME)
       private String name;

       @Mask(MaskType.MOBILE)
       private String phone;
   }
   ```

2. **动态规则脱敏（配置驱动）**
   通过配置中心下发 `team4u.mask.rules`，覆盖 Map Key、方法参数名、第三方 DTO 字段等场景：

   ```json
   {
     "*": {
       "mobile": "MOBILE",
       "appSecret": "PASSWORD"
     },
     "com.demo.PaymentReq": {
       "bankCardNo": "BANK_CARD_NO"
     }
   }
   ```

#### 规则与优先级（日志视角）

- 字段序列化时：`@Mask` **优先**于外部规则。
- 外部规则匹配顺序：**类名精确匹配** > **`*` 全局通配符**。
- 未命中规则时保持原值（建议显式配置关键字段）。

#### 启动建议

- 使用 `LogBootstrap.start()` 时，日志模块会自动联动初始化 `MaskBootstrap`。
- 若你依赖方法参数名进行脱敏（如 `String mobile`），建议开启编译参数 `-parameters`，避免参数名退化为 `arg0/arg1`。
## 单元测试与日志验证

得益于结构化的设计，本模块具有极佳的可测试性。框架内置了 `TestLogHelper` 工具类，您可以在单元测试中轻松捕获并断言日志内容。

以下是单元测试中的推荐做法：

```java
import com.team4u.framework.log.support.TestLogHelper;

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
    Assert.assertTrue(json.contains("**伦"));
    
} finally {
    // 停止当前 helper 的捕获挂载
    helper.stop();
    // 如需测试间全局隔离，请显式重置对应单例状态
    LogEngine.getInstance().reset();
}
```

### 配置动态热重载
 
本模块所有的动态治理功能都通过 `team4u-config` 进行热重载，不同维度的规则拥有独立的配置键。

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

我们不需要写任何 AOP 代码，只需向配置中心推送动态规则：
 
**1) 代理规则 (Key: `team4u.log.proxy`)：**
 
```json
{
  "com.thirdparty.ThirdPartySmsClient": {
    "methods": ["send", "queryBalance"],  // 指定要自动打印日志的方法名（支持 "*" 全拦截）
    "slowThreshold": 500,                 // 超过 500ms 自动标记为慢日志 (WARN)
    "ignoreExceptions": ["com.thirdparty.BusinessException"] // 业务异常降级为 WARN (需填入全限定类名)
  }
}
```
 
**2) 全局脱敏规则 (Key: `team4u.mask.rules`)：**
 
```json
{
  "*": {
    "mobile": "MOBILE",         // 入参如果有同名字段，自动脱敏
    "appSecret": "PASSWORD"    // 凭证脱敏
  }
}
```

#### 3. 创建动态代理并使用

在将第三方客户端注入到 Spring 容器（或业务代码）之前，使用 `LogProxyFactory` 对其进行包装：

```java
// 1. 原始第三方实例
ThirdPartySmsClient rawClient = new ThirdPartySmsClient();

// 2. 一行代码生成动态代理（底层基于 team4u-proxy 的 ByteBuddy 引擎）
ThirdPartySmsClient safeClient = LogProxyFactory.createDynamicProxy(rawClient);

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
    "req": {
      "mobile": "138*****678",    // <-- 手机号被自动脱敏
      "appSecret": "******",      // <-- 凭证被自动脱敏
      "content": "您的验证码是 9527"
    },
    "resp": {
      "status": "OK"
    }
  }
}
```

### 动态靶向染色
在排查线上问题时，可能需要将特定用户、特定环境或特定业务标识的日志级别从 `INFO` / `TRACE` 动态提升到 `DEBUG`。

#### 多维匹配上下文
染色规则支持从以下多个维度提取变量进行匹配（优先级从高到低）：
- 业务载荷 (Payload)：日志方法中传入的 Map 参数。
  - 直接访问：可以直接使用 Key 名（如 `orderId == 'ORD001'`），引擎会自动从 Payload 中检索。
  - 整体访问：使用 `payload` 关键字访问完整的业务数据 Map（如 `payload:size > 5` 判断字段数量）。
- MDC 属性 (MDC)：SLF4J MDC 中的全量属性（如 `traceId`）。
- 基础元数据 (Metadata)：自动注入的 `meta_action`, `meta_level`, `meta_logger`, `meta_thread`, `meta_status`, `meta_durationMs` 等。

#### 开发 API 使用
由于系统已全面重构为高性能的 Pull 模型，除了标准 SLF4J 的 MDC 外，你还可以通过注册自定义寻值源来扩展业务属性。

#### 动态配置示例
在配置中心对应的 Key (`team4u.log.dyeing`) 中编写规则：
 
```json
[
  {
    "id": "vip_order_debug",
    "condition": "orderAmount > 1000 && env == 'prod'",
    "targetLevel": "DEBUG"
  },
  {
    "id": "payload_check",
    "condition": "payload:size > 0 && meta_action == 'PAYMENT'",
    "targetLevel": "TRACE"
  }
]
```

#### 高级扩展：自定义寻值源 (Pull 模型)
如果内置的维度不足以满足需求，可以通过实现 `LogContextSource` 并注册或通过 SPI 自动发现来扩展。相比于旧版的 Push 模型，Pull 模型按需加载，性能更高。

```java
// 注册自定义寻值源
LogContext.addSource((event, key) -> {
    if ("custom_key".equals(key)) {
        return "custom_value"; // 只有在规则引擎用到 "custom_key" 时才会执行此逻辑
    }
    return null;
});
```
*效果：一旦条件匹配（利用 `team4u-criterion` 表达式引擎），日志级别将被自动篡改，并在 Payload 中打上标记 `"dyeingRuleMatched": "vip_user_debug"`。*

### 防雪崩限流与长度截断
为了防止底层数据库宕机导致的异常日志风暴（拖垮磁盘 IO 和日志采集系统）以及超大报文引起的 OOM，模块内置了双重保护机制。

*   雪崩限流：针对同一种类异常（特征：`Action` + `ExceptionClassName`），默认限制 10条 / 秒。超出部分的日志将被静默丢弃，系统仅输出一条简短的超限告警。
*   字段级截断（Value-Level）：在 Jackson 序列化阶段，单个 `String` 字段若超过 `maxStringLength`（默认 2000），将直接被截断并追加 `... [Truncated len:xxx]`。这能有效防止大报文在序列化过程中消耗过多内存和 CPU。
*   字节数组防御：为了防止将大文件（`byte[]`）序列化为巨大的 Base64 字符串，系统会拦截所有 `byte[]` 类型的输出，统一替换为大小提示（如 `[byte[] size: 1024 bytes]`）。
*   日志级截断（Log-Level）：单条 JSON 日志序列化后的字符串整体默认最大长度为 `maxLogLength`（默认 5000）。超过部分将被截断并追加 `... [Truncated at 5000]`。

动态调整阈值 (Key: `team4u.log.finops`)：
```json
{
  "maxLogLength": 5000,
  "maxStringLength": 2000,
  "errorLimitPerSecond": 10
}
```

### 配置中心 Key 汇总
 
当你向配置中心推送以下 JSON 时，系统会自动热生效：
 
| 功能名称     | 配置键 (Key)        | 内容结构示例                                                 |
| :----------- | :------------------ | :----------------------------------------------------------- |
| **脱敏规则** | `team4u.mask.rules` | `{"*": {"mobile": "MOBILE"}}`                                |
| **代理追踪** | `team4u.log.proxy`  | `{"com.a.B": {"methods": ["*"]}}`                            |
| **条件染色** | `team4u.log.dyeing` | `[{"id": "d1", "condition": "...", "targetLevel": "DEBUG"}]` |
| **成本保护** | `team4u.log.finops` | `{"maxLogLength": 5000, "errorLimitPerSecond": 10}`          |



## 自定义扩展与底层配置注意事项

### MDC 全量上下文支持
特别指出，在执行“动态染色”匹配时，框架会自动提取当前线程所有的 MDC 变量。这些变量会直接暴露给规则引擎（例如 `traceId`, `orderId`, `userId`）。如果开发者在网关层统一塞入关键的流量标识（如 `userId`, `tenantId` 等），配合染色规则即可大幅提升排障效率。

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

1.  拦截器流水线：由 `LogInterceptorManager` 调度。
    *   MdcEnrichInterceptor：提取链路追踪 ID。
    *   TargetedDyeingInterceptor：执行靶向染色（提权/降权）。
    *   RateLimitInterceptor：异常日志风暴限流保护。
    *   自定义拦截器：按优先级执行用户扩展逻辑。
2.  序列化与脱敏：由 `LogEngine` 调用定制后的 `ObjectMapper` 进行 JSON 化。
    *   字符串截断：单个字段超长截断（`maxStringLength`）。
    *   字节数组防御：拦截 `byte[]` 输出。
    *   动态脱敏：执行 `FastMasker` 极速脱敏。
3.  最终输出：由 `LogAppender` 执行。
    *   整条日志截断：JSON 整体超长截断（`maxLogLength`）。
    *   落地输出：默认通过 SLF4J 打印。
