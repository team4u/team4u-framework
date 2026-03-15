[返回总目录](../README.md)

# 日志模块

`team4u-log` 是 Team4u 框架里的结构化日志治理模块。它解决的不是“怎么打印一行日志”，而是“怎样把业务日志稳定地做成可检索、可追踪、可治理、可控成本”。

它适合这几类场景：

- 业务日志希望统一成 JSON，减少字符串拼接。
- 需要自动记录方法入参、出参、耗时和异常。
- 需要对敏感字段做脱敏，且规则支持动态调整。
- 线上排障时希望临时提权某类日志，而不是全局打开 DEBUG。
- 希望防止超长日志和异常风暴把磁盘、采集链路或告警系统拖垮。

## 目录

- [阅读导航](#阅读导航)
- [3 分钟快速开始](#3-分钟快速开始)
- [使用注意事项](#使用注意事项)
- [推荐实践](#推荐实践)
- [核心概念](#核心概念)
- [常见接入方式](#常见接入方式)
- [配置总表](#配置总表)
- [进阶文档](#进阶文档)

## 阅读导航

如果你是第一次接入，建议按这个顺序读：

1. 先看“3 分钟快速开始”，把最小闭环跑起来。
2. 再看“使用注意事项”，先避开最常见的坑。
3. 然后看“推荐实践”，决定你的场景该用哪种接入方式。
4. 需要理解字段模型和规则匹配方式时，再看“核心概念”。
5. 需要排障、完整示例或实现原理时，直接跳到文末的进阶文档。

## 3 分钟快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-log</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 启动模块

```java
import com.team4u.framework.log.LogBootstrap;

public class Application {
    public static void main(String[] args) {
        LogBootstrap.start();
    }
}
```

默认启动会联动初始化日志模块依赖的动态治理能力，包括脱敏、染色、FinOps 限制和第三方代理规则。

### 3. 写一条最小业务日志

```java
import com.team4u.framework.log.Loggers;
import org.slf4j.MDC;

MDC.put("traceId", "tid-998877");

Loggers.of(OrderService.class)
       .action("CreateOrder")
       .put("orderId", "ORD-12345")
       .put("mobile", "13800138000")
       .success()
       .log();
```

默认输出示例：

```json
{
  "loggerName": "com.demo.OrderService",
  "level": "INFO",
  "traceId": "tid-998877",
  "action": "CreateOrder",
  "status": "success",
  "durationMs": -1,
  "payload": {
    "orderId": "ORD-12345",
    "mobile": "13800138000"
  },
  "suppressed": false
}
```

### 4. 加一条动态脱敏规则，再看输出变化

向配置中心下发 `team4u.mask.rules`：

```json
{
  "*": {
    "mobile": "MOBILE"
  }
}
```

同样的业务代码再次打印时，输出会变成：

```json
{
  "loggerName": "com.demo.OrderService",
  "level": "INFO",
  "traceId": "tid-998877",
  "action": "CreateOrder",
  "status": "success",
  "durationMs": -1,
  "payload": {
    "orderId": "ORD-12345",
    "mobile": "138*****000"
  },
  "suppressed": false
}
```

这就是最小闭环：启动模块、打印结构化日志、通过动态配置改变输出结果。

完整的 0 到 1 场景演示见 [docs/walkthrough.md](./docs/walkthrough.md)。

## 使用注意事项

这些不是小提示，而是最常见的踩坑点：

- `derive()` 对 `payload` 只做浅拷贝。顶层 Map 会复制，内部可变对象不会深拷贝。
- 如果你依赖方法参数名做脱敏或日志展示，建议开启 Maven 编译参数 `-parameters`，否则参数名可能退化成 `arg0`、`arg1`。
- 染色规则里的基础元数据字段必须写成 `meta_*`，例如 `meta_action`、`meta_status`，不能直接写裸字段名。
- 输出前还会经过脱敏、超长字段截断、整条日志截断和异常限流；“为什么少了”不一定是没执行，有可能是保护机制生效。
- `traceId` 默认从 MDC 里的 `traceId` 读取。如果你的链路字段叫 `requestId`，需要显式调整提取 key。
- `around()` 适合标准的“执行并自动记结果”场景；如果你需要显式打印开始日志、分阶段补字段，优先用 `begin()` / `logStart()`。

## 推荐实践

### 普通业务日志

优先使用 `Loggers`。这是最直接、可控、最适合作为团队默认写法的入口。

```java
Loggers.of(PaymentService.class)
       .action("PayOrder")
       .put("orderId", orderId)
       .put("amount", amount)
       .success()
       .log();
```

### 自动记录入参、出参、耗时和异常

优先使用 `@AutoLogTrace`，适合服务层、门面层、SDK 封装层这类“方法边界清晰”的位置。

```java
import com.team4u.framework.log.proxy.AutoLogTrace;

public class UserService {

    @AutoLogTrace(action = "RegisterUser", slowThreshold = 200)
    public String register(UserReq req) {
        return "SUCCESS";
    }
}
```

创建代理：

```java
UserService service = LogProxyFactory.createProxy(new UserService());
```

### 第三方 SDK 或无法改源码的对象

优先使用 `createDynamicProxy()`，再配合 `team4u.log.proxy` 做方法拦截范围、慢日志阈值和业务异常降级。

```java
ThirdPartySmsClient client = LogProxyFactory.createDynamicProxy(new ThirdPartySmsClient());
```

### 规则治理

日志级别提权、第三方代理规则、脱敏规则、长度与限流阈值都优先走配置中心，而不是写死在代码里。

### 单测断言

日志行为需要验证时，优先使用 `TestLogHelper`，不要在测试里读控制台输出。

```java
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.support.TestLogHelper;

TestLogHelper helper = TestLogHelper.start();
try {
    userService.register(new UserReq("周杰伦", "13800138000"));
    LogEvent event = helper.lastEvent();
    String json = helper.lastJson();
} finally {
    helper.stop();
}
```

## 核心概念

### 1. `LogEvent` 是统一输出模型

无论你是手写 `Loggers`、用 `@AutoLogTrace`，还是通过动态代理记录，最后都会归一成一个 `LogEvent` 再进入处理流水线。

常见字段包括：

- `loggerName`: 哪个类打的日志
- `level`: 最终输出级别
- `traceId`: 链路标识
- `action`: 业务动作
- `status`: 业务状态
- `durationMs`: 耗时
- `payload`: 业务载荷

### 2. 业务字段进入 `payload`

通过 `put()`、`putAll()`、自动代理采集到的业务参数和返回值，最终都进入 `payload`。这意味着：

- 业务字段要按“可检索字段”思路命名。
- 染色、脱敏、测试断言通常都围绕 `payload` 展开。
- 规则里可以直接用 payload 的 key，也可以用 `payload` 访问整体 Map。

### 3. 规则匹配同时看 `payload`、MDC 和 `meta_*`

动态染色时，规则引擎可见的上下文主要来自三类数据：

- `payload`: 业务字段
- MDC: 例如 `traceId`、`tenantId`
- `meta_*`: 框架注入的元数据，例如 `meta_action`、`meta_level`、`meta_status`、`meta_durationMs`

简单理解为：业务上下文看 `payload`，链路上下文看 MDC，日志自身属性看 `meta_*`。

### 4. `derive()` 适合做模板，但它不是深拷贝

你可以把常用公共字段预置成模板，然后在具体业务点派生：

```java
private static final Loggers BASE_LOG = Loggers.of(OrderService.class)
        .put("module", "OrderCenter")
        .put("version", "v2");

BASE_LOG.derive()
        .action("CreateOrder")
        .put("orderId", "ORD-12345")
        .success()
        .log();
```

但如果模板里放了可变对象，派生后仍可能共享内部引用。

### 5. 最终输出前还会经过治理链路

打印一条日志不等于马上原样输出。正式输出前还可能发生：

- 从 MDC 补链路字段
- 命中染色规则并调整级别
- 对敏感字段做脱敏
- 对超长字段和整条日志做截断
- 对异常风暴做限流

## 常见接入方式

### 手动打印一条结果日志

```java
Loggers.of(OrderService.class)
       .action("CreateOrder")
       .duration(120)
       .put("orderId", "ORD-12345")
       .success()
       .log();
```

### 打一段区间日志

```java
LogSpan span = Loggers.of(OrderService.class)
        .action("CreateOrder")
        .put("orderId", orderId)
        .begin()
        .logStart();

try {
    businessService.doSomething();
    span.success().log();
} catch (Exception e) {
    span.failed(e).log();
}
```

### 用 `around()` 简化标准执行路径

```java
Loggers.of(OrderService.class)
       .action("CreateOrder")
       .put("orderId", orderId)
       .around(() -> businessService.doSomething());
```

### 用 `@AutoLogTrace` 做方法边界追踪

```java
@AutoLogTrace(
    action = "UserLogin",
    slowThreshold = 200,
    ignoreExceptions = {BusinessException.class}
)
public void login(String username) {
    // ...
}
```

### 用 `createDynamicProxy()` 包住第三方实例

```java
ThirdPartySmsClient rawClient = new ThirdPartySmsClient();
ThirdPartySmsClient safeClient = LogProxyFactory.createDynamicProxy(rawClient);
```

## 配置总表

| 配置 Key | 作用 | 是否热重载 | 典型场景 | 说明 |
| :-- | :-- | :-- | :-- | :-- |
| `team4u.mask.rules` | 动态脱敏规则 | 是 | Map Key、方法参数名、第三方 DTO 字段脱敏 | 见 [3 分钟快速开始](#3-分钟快速开始) 和 [docs/walkthrough.md](./docs/walkthrough.md) |
| `team4u.log.proxy` | 第三方动态代理规则 | 是 | 指定拦截方法、慢日志阈值、业务异常降级 | 见 [常见接入方式](#常见接入方式) 和 [docs/walkthrough.md](./docs/walkthrough.md) |
| `team4u.log.dyeing` | 条件染色规则 | 是 | 临时提权某类日志到 `DEBUG` / `TRACE` | 见 [docs/walkthrough.md](./docs/walkthrough.md) 和 [docs/faq.md](./docs/faq.md) |
| `team4u.log.finops` | 成本保护阈值 | 是 | 控制最大日志长度、单字段长度、异常限流 | 见 [使用注意事项](#使用注意事项) 和 [docs/faq.md](./docs/faq.md) |

## 进阶文档

- 完整接入示例：[`docs/walkthrough.md`](./docs/walkthrough.md)
- FAQ / 排障手册：[`docs/faq.md`](./docs/faq.md)
- 实现原理与扩展机制：[`docs/architecture.md`](./docs/architecture.md)

如果你只是接入业务日志，到这里通常已经够用了。需要理解 Pull/Push 模型、拦截器流水线、Appender 扩展、自定义上下文源时，再看 `architecture.md`。
