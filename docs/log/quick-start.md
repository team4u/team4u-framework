# 快速开始

本文介绍如何在项目中快速引入并使用 `team4u-log` 进行结构化日志打印与动态治理。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-log</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

> [!NOTE]
> 若需使用 `@AutoLogTrace` 自动获取方法入参的真实参数名，建议在 `pom.xml` 的 `maven-compiler-plugin` 中开启 `-parameters` 编译参数：
> ```xml
> <plugin>
>     <groupId>org.apache.maven.plugins</groupId>
>     <artifactId>maven-compiler-plugin</artifactId>
>     <configuration>
>         <compilerArgs>
>             <arg>-parameters</arg>
>         </compilerArgs>
>     </configuration>
> </plugin>
> ```

---

## 启动日志治理模块 (`LogBootstrap`)

在应用启动时（如 `main` 函数或 Spring Boot 启动类）初始化日志系统：

```java
import com.team4u.framework.log.LogBootstrap;

public class Application {
    public static void main(String[] args) {
        // 启动并初始化脱敏、染色、FinOps 与代理规则流水线
        LogBootstrap.start();
        
        // 业务启动逻辑...
    }
}
```

> [!TIP]
> `LogBootstrap.start()` 内部具备防重复启动保护；如果需要传入自定义的 `ConfigManager` 或 `Criteria` 引擎，可调用 `LogBootstrap.start(LogBootstrap.Options.builder().configManager(myConfigManager).build())`。

---

## 打印第一条结构化业务日志

通过 Fluent API 流式构建业务日志：

```java
import com.team4u.framework.log.Loggers;
import org.slf4j.MDC;

public class OrderService {

    public void createOrder(String orderId, String mobile) {
        // 设置 MDC 中的 traceId（MdcEnrichInterceptor 会自动抽取并注入外层）
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

### 控制台标准 JSON 输出：
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

## 联动动态脱敏

无需重启应用，在配置中心下发脱敏配置 `team4u.mask.rules`：

```json
{
  "*": {
    "mobile": "MOBILE"
  }
}
```

再次执行上述日志打印代码，输出中的 `payload.mobile` 将自动完成掩码脱敏：

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
    "mobile": "138*****000"
  },
  "suppressed": false
}
```

---

## 下一步

- 掌握完整 Fluent API 与耗时区间统计：[结构化流式日志 (Loggers)](log-loggers.md)
- 使用注解实现方法出入参全自动拦截：[方法切面追踪 (@AutoLogTrace)](log-auto-trace.md)
- 动态排障染色与成本保护：[动态治理与 FinOps 成本保护](log-governance.md)
- 深入底层流水线与单元测试断言：[架构原理与模型设计](log-architecture.md)
