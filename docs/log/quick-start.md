# 快速开始

本文介绍如何在 `team4u-log-core` 与 `team4u-log-governance` 之间选择，并完成第一条结构化日志输出。

---

## 引入依赖

只需 `Loggers`、`LogSpan`、内存捕获与 SLF4J 输出时，引入 provider-free 的核心：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-log-core</artifactId>
</dependency>
```

核心默认使用 `PlainTextLogSerializer`：`LogEngine.toJson(LogEvent)` 和 `TestLogHelper.lastJson()` 返回未经脱敏的 RAW/UNMASKED `toString` 明文，而不是 JSON；可能包含手机号、身份证等敏感值。需要自定义格式时，通过 `LogEngine.builder().serializer(...)` 显式注入 `LogSerializer`，并通过 `.interceptor(...)` / `.interceptors(...)` 注入拦截器。

需要 Jackson JSON、配置驱动脱敏/染色/FinOps、动态代理或 Spring AOP 时，只引入治理 artifact：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-log-governance</artifactId>
</dependency>
```

`team4u-log-governance` 传递 `team4u-log-core`、`team4u-serializer-jackson` 与 Jackson；不要为它重复声明 provider 或 Jackson 依赖。

> [!NOTE]
> 若需使用 `@AutoLogTrace` 自动获取方法入参的真实参数名，建议在 `pom.xml` 的 `maven-compiler-plugin` 中开启 `-parameters` 编译参数。

---

## 启动日志治理 (`LogBootstrap`)

在应用启动时调用：

```java
import com.team4u.framework.log.LogBootstrap;

public class Application {
    public static void main(String[] args) {
        LogBootstrap.start();

        // 业务启动逻辑...
    }
}
```

`start()` 会装配 `JacksonLogSerializer` 和治理拦截器，安装新的全局 `LogEngine`，并保存启动前的 engine。`start(Options)` 可显式传入 `ConfigManager` 和 `Criteria`；重复 start 被忽略。`reconfigure(Options)` 仅在 STARTED 状态可用；失败时优先回滚上一份 options，回滚也失败则进入 FAILED。

`stop()` 只在 bootstrap 仍拥有当前 engine 时恢复启动前 engine；如果外部已安装了更新的 engine，它不会覆盖新 owner，只复位已脱离的治理 engine 并清理 governance 仓库与限流状态。无论恢复结果如何，`stop()` 都会执行组件清理并进入 STOPPED。

---

## 打印第一条日志

```java
import com.team4u.framework.log.Loggers;
import org.slf4j.MDC;

public class OrderService {

    public void createOrder(String orderId, String mobile) {
        MDC.put("traceId", "tid-998877");

        Loggers.of(OrderService.class)
                .action("CreateOrder")
                .put("orderId", orderId)
                .put("mobile", mobile)
                .success()
                .log();
    }
}
```

在 `team4u-log-core` 中，`LogEngine.toJson(event)` 是未经脱敏的 RAW/UNMASKED 明文 `toString` 输出。启动 `team4u-log-governance` 后，活动 serializer 变为 Jackson，同一条事件输出 JSON：

```json
{
  "loggerName": "com.demo.OrderService",
  "level": "INFO",
  "traceId": "tid-998877",
  "action": "CreateOrder",
  "status": "success",
  "durationMs": -1,
  "payload": {
    "orderId": "ORD-10086",
    "mobile": "13800138000"
  },
  "suppressed": false
}
```

---

## 测试断言

`TestLogHelper` 捕获 `LogEvent`，`lastJson()` 始终使用当前全局 engine 的活动 serializer：

```java
TestLogHelper helper = TestLogHelper.start();
try {
    Loggers.of(OrderService.class).action("CreateOrder").success().log();

    LogEvent event = helper.lastEvent();
    String serialized = helper.lastJson(); // core: RAW/UNMASKED plain text; governance started: JSON
} finally {
    helper.stop();
}
```

---

## 下一步

- 掌握完整 Fluent API 与耗时区间统计：[结构化流式日志 (Loggers)](log-loggers.md)
- 使用注解实现方法出入参全自动拦截：[方法切面追踪 (@AutoLogTrace)](log-auto-trace.md)
- 动态排障染色与成本保护：[动态治理与 FinOps 成本保护](log-governance.md)
- 深入底层流水线与单元测试断言：[架构原理与模型设计](log-architecture.md)
