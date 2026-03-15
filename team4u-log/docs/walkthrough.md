# team4u-log Walkthrough

这份文档给出一个从 0 到 1 的贯穿示例：启动模块、打印业务日志、打开动态脱敏、再打开动态染色，最后补上第三方 SDK 代理。

## 场景目标

假设有一个创建订单接口，需要满足这几个要求：

- 每条日志带 `traceId`
- 输出结构化 JSON
- 手机号自动脱敏
- 只把特定订单的日志临时提权到 `DEBUG`
- 调第三方短信 SDK 时也自动记录入参、出参、耗时

## 1. 启动日志模块

```java
import com.team4u.framework.log.LogBootstrap;

public class Application {
    public static void main(String[] args) {
        LogBootstrap.start();
    }
}
```

如果你要替换配置管理器或表达式引擎实例，可以传 `LogBootstrap.Options`：

```java
LogBootstrap.start(LogBootstrap.Options.builder()
        .configManager(globalConfigManager)
        .criteria(customCriteria)
        .build());
```

## 2. 先跑最小业务代码

```java
import com.team4u.framework.log.Loggers;
import org.slf4j.MDC;

public class OrderService {

    public void create(String orderId, String mobile) {
        MDC.put("traceId", "tid-10001");

        Loggers.of(OrderService.class)
               .action("CreateOrder")
               .put("orderId", orderId)
               .put("mobile", mobile)
               .put("channel", "app")
               .success()
               .log();
    }
}
```

此时输出大致是：

```json
{
  "loggerName": "com.demo.OrderService",
  "level": "INFO",
  "traceId": "tid-10001",
  "action": "CreateOrder",
  "status": "success",
  "durationMs": -1,
  "payload": {
    "orderId": "ORD-1001",
    "mobile": "13800138000",
    "channel": "app"
  },
  "suppressed": false
}
```

## 3. 打开动态脱敏

下发配置 `team4u.mask.rules`：

```json
{
  "*": {
    "mobile": "MOBILE"
  }
}
```

同样的代码，输出会变成：

```json
{
  "loggerName": "com.demo.OrderService",
  "level": "INFO",
  "traceId": "tid-10001",
  "action": "CreateOrder",
  "status": "success",
  "durationMs": -1,
  "payload": {
    "orderId": "ORD-1001",
    "mobile": "138*****000",
    "channel": "app"
  },
  "suppressed": false
}
```

如果敏感字段来自 DTO 字段，也可以直接使用 `@Mask`，注解规则优先于动态规则。

## 4. 打开动态染色

你只想排查一个异常订单，不想全局开 `DEBUG`，可以下发 `team4u.log.dyeing`：

```json
[
  {
    "id": "order_debug",
    "condition": "orderId == 'ORD-1001' && meta_action == 'CreateOrder'",
    "targetLevel": "DEBUG"
  }
]
```

当 `orderId` 命中时，同样一条日志会被提权到 `DEBUG`，并附带命中的规则标识：

```json
{
  "loggerName": "com.demo.OrderService",
  "level": "DEBUG",
  "traceId": "tid-10001",
  "action": "CreateOrder",
  "status": "success",
  "durationMs": -1,
  "payload": {
    "orderId": "ORD-1001",
    "mobile": "138*****000",
    "channel": "app",
    "dyeingRuleMatched": "order_debug"
  },
  "suppressed": false
}
```

写染色规则时要记住两点：

- 业务字段通常直接取 `payload` 中的 key，例如 `orderId`
- 日志元数据必须写成 `meta_*`，例如 `meta_action`

## 5. 给区间日志补耗时

如果你还想记录开始和结束，并自动计算耗时，用 `begin()`：

```java
LogSpan span = Loggers.of(OrderService.class)
        .action("CreateOrder")
        .put("orderId", orderId)
        .begin()
        .logStart();

try {
    doCreate(orderId);
    span.success().log();
} catch (Exception e) {
    span.failed(e).log();
}
```

如果不需要显式的开始日志，也可以直接用 `around()`：

```java
Loggers.of(OrderService.class)
       .action("CreateOrder")
       .put("orderId", orderId)
       .around(() -> doCreate(orderId));
```

## 6. 接第三方 SDK 时用动态代理

假设你有一个无法改源码的短信客户端：

```java
public class ThirdPartySmsClient {
    public SmsResponse send(String mobile, String appSecret, String content) {
        return new SmsResponse("OK");
    }
}
```

先下发代理规则 `team4u.log.proxy`：

```json
{
  "com.thirdparty.ThirdPartySmsClient": {
    "methods": ["send"],
    "slowThreshold": 500,
    "ignoreExceptions": ["com.thirdparty.BusinessException"]
  }
}
```

再下发脱敏规则：

```json
{
  "*": {
    "mobile": "MOBILE",
    "appSecret": "PASSWORD"
  }
}
```

包装实例：

```java
ThirdPartySmsClient rawClient = new ThirdPartySmsClient();
ThirdPartySmsClient safeClient = LogProxyFactory.createDynamicProxy(rawClient);
safeClient.send("13812345678", "sk_live_123abc", "您的验证码是 9527");
```

输出示例：

```json
{
  "loggerName": "com.thirdparty.ThirdPartySmsClient",
  "level": "INFO",
  "action": "send",
  "status": "success",
  "durationMs": 12,
  "payload": {
    "req": {
      "mobile": "138*****678",
      "appSecret": "******",
      "content": "您的验证码是 9527"
    },
    "resp": {
      "status": "OK"
    }
  }
}
```

## 7. 最后补上测试断言

```java
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.support.TestLogHelper;

TestLogHelper helper = TestLogHelper.start();
try {
    orderService.create("ORD-1001", "13800138000");
    LogEvent event = helper.lastEvent();
    String json = helper.lastJson();
} finally {
    helper.stop();
}
```

推荐至少断言：

- `action` 是否符合预期
- `status` 是否正确
- 关键字段是否在 `payload`
- 脱敏后的 JSON 是否符合预期

## 推荐落地顺序

如果你要在业务项目里推广这套日志，建议按这个顺序落地：

1. 先统一 `LogBootstrap.start()` 和 `Loggers` 基础写法。
2. 再对高价值接口补 `@AutoLogTrace`。
3. 然后把脱敏规则和染色规则接入配置中心。
4. 最后再给第三方 SDK 接上 `createDynamicProxy()`。

这样风险最低，也最容易让团队快速看到收益。
