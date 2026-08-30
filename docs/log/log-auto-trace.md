# 方法切面追踪 (@AutoLogTrace)

对于服务层接口、RPC 调用或通用 SDK 方法，手动编写日志较为繁琐。`@AutoLogTrace` 提供了全自动的方法切面追踪能力，自动记录入参、出参、执行耗时，并支持慢调用告警与业务异常降级。该能力属于 `team4u-log-governance`；`team4u-log` 只提供事件与基础日志 API。

---

## 注解参数详解

```java
package com.team4u.framework.log.proxy;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoLogTrace {
    /**
     * 业务动作名（默认为空，为空时自动取当前被调用的方法名）
     */
    String action() default "";

    /**
     * 慢调用耗时阈值（毫秒）。
     * 若方法执行耗时超过该值：
     * 1. 日志状态标记为 "slow_success"
     * 2. 日志级别自动提升为 WARN
     * 3. payload 中记录 "slowThreshold" 阈值
     */
    long slowThreshold() default -1;

    /**
     * 业务可预期的异常类列表。
     * 当抛出的异常属于列表中的类或其子类时：
     * 1. 日志状态标记为 "business_error"
     * 2. 日志级别为 WARN，payload 记录 "errMsg"
     * 3. 不会以 ERROR 级别打印完整的异常堆栈
     */
    Class<? extends Throwable>[] ignoreExceptions() default {};
}
```

---

## 追踪与脱敏处理细节 (`LogTraceSupport`)

切面拦截器在执行方法追踪时，内部由 `LogTraceSupport` 提供核心支持：

1. **真实类名推断**：
   `LogTraceSupport.getTargetClass(...)` 能够智能识别并穿透 `$$` 及 `ByteBuddy` 动态生成的代理子类，准确获取底层真实的业务类名。
2. **参数名自动提取与主动脱敏**：
   - 优先通过反射读取编译期保留的参数名（需开启 `-parameters` 编译参数），若未保留则回退为 `arg0`, `arg1`...；
   - 对字符串类型入参，切面会主动查询 `MaskRuleRepository` 中针对该类和参数名的脱敏规则，就地完成掩码，防止敏感参数泄漏。
3. **返回值记录**：
   正常返回时，方法的返回值将保存在 `payload.resp` 中。
4. **日志级别与状态映射规则**：

| 执行情况 | 最终 `status` | 最终 `level` | `payload` 附加字段 | 异常堆栈 |
| :--- | :--- | :--- | :--- | :--- |
| 正常执行且耗时 $\le$ slowThreshold | "success" | `INFO` | "resp": result` | 无 |
| 正常执行但耗时 $>$ slowThreshold | "slow_success" | `WARN` | "resp": result`, "slowThreshold": N` | 无 |
| 抛出 `ignoreExceptions` 中的异常 | "business_error" | `WARN` | "errMsg": ex.getMessage()` | 无 |
| 抛出未声明的非预期异常 | "failed" | `ERROR` | 入参字段 | 记录完整 `exception` |

---

## 接入方式

### Spring Bean 接入（推荐）

在 Spring 配置中引入 `LogSpringConfiguration`，即可使 Spring 托管的所有 Bean 上的 `@AutoLogTrace` 注解生效：

```java
import com.team4u.framework.log.spring.LogSpringConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(LogSpringConfiguration.class)
public class LogConfig {
}
```

#### 业务 Service 使用示例：
```java
@Service
public class UserService {

    @AutoLogTrace(
        action = "UserRegister", 
        slowThreshold = 300, 
        ignoreExceptions = {BizException.class, IllegalArgumentException.class}
    )
    public UserRegisterResp register(String username, String mobile, String password) {
        // 业务处理逻辑
        return new UserRegisterResp("UID-10086", "SUCCESS");
    }
}
```

---

### 非 Spring 普通对象接入 (`LogProxyFactory.createProxy`)

对于在纯 Java 环境下运行或自行实例化的对象：

```java
import com.team4u.framework.log.proxy.LogProxyFactory;

UserService rawService = new UserServiceImpl();
// 创建带注解拦截的代理对象
UserService proxyService = LogProxyFactory.createProxy(rawService);

// 调用时自动拦截并输出结构化日志
proxyService.register("tom", "13800138000", "p@ssword");
```

---

### 第三方不可修改源码类：动态配置驱动 (`createDynamicProxy`)

对于引入的第三方 SDK 或不可修改源代码的 Client 类，无法在源码上添加 `@AutoLogTrace` 注解。可以使用动态代理配合配置中心规则进行治理：

#### 第一步：创建动态代理实例
```java
ThirdPartyPayClient rawClient = new ThirdPartyPayClient();
ThirdPartyPayClient proxyClient = LogProxyFactory.createDynamicProxy(rawClient);
```

#### 第二步：在配置中心下发 `team4u.log.proxy` 规则
```json
{
  "com.thirdparty.sdk.ThirdPartyPayClient": {
    "methods": ["pay", "refund"],
    "slowThreshold": 500,
    "ignoreExceptions": [
      "com.thirdparty.sdk.PaymentDeclinedException"
    ]
  }
}
```

> [!TIP]
> - `methods` 配置为 `["*"]` 时，将自动拦截该类的所有 public 方法。
> - 若当前调用的方法未在 `methods` 名单中，`DynamicLogProxyInterceptor` 将以近乎零损耗直接放行。
