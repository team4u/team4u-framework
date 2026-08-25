# 注解与代理模式

除了编程式调用，`team4u-retry-proxy` 提供了基于 `@Retryable` 注解、方法调用快照与反射回放的声明式重试能力。

---

## 核心注解

### 1. `@Retryable`
标注在接口、类或具体方法上：

```java
import com.team4u.framework.retry.proxy.RetryMode;
import com.team4u.framework.retry.proxy.Retryable;

public interface PaymentService {

    // 标注策略名与执行模式
    @Retryable(policy = "pay-notify-policy", mode = RetryMode.MANAGED)
    void notifyMerchant(String orderId, String payload);
}
```

- `policy`: 策略名称，关联静态或动态配置中心的重试规则。
- `mode`: 执行模式，可选 `RetryMode.INLINE`（默认）或 `RetryMode.MANAGED`。

---

### 2. `@RetryIgnore` 与幂等键影响

标记在方法参数上，指示在生成持久化调用快照时忽略该参数（后台回放时该位置将以 `null` 注入）：

```java
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;
import javax.servlet.http.HttpServletRequest;

public void notifyMerchant(
        String orderId,
        String payload,
        @RetryIgnore HttpServletRequest request // 忽略不可序列化或无状态的上下文参数
) {
    // 业务逻辑
}
```

> [!IMPORTANT]
> **`@RetryIgnore` 核心约束与幂等性保证**：
> 1. **禁止标记基本数据类型 (Primitive)**：`@RetryIgnore` 不能标注在 `int`、`boolean`、`long` 等基本类型参数上（因为反序列化回放注入 `null` 时会引发反射 NPE），否则在初始化时即抛出 `IllegalStateException`。
> 2. **对幂等键计算的决定性影响**：
>    - MANAGED 代理模式下的默认业务幂等键计算公式为：
>      `SHA-256( targetTypeName#methodName | arg0Type:isIgnored:serializedValue, arg1Type:isIgnored:serializedValue, ... )`
>    - 被 `@RetryIgnore` 标记的参数其 `serializedValue` 恒为 `null`，因此**传入不同的 request 实例不会改变幂等键计算结果**，保证业务幂等维度的纯粹性。

---

## 代理元数据与桥接方法解析 (`RetryMethodResolver`)

在复杂的继承体系与泛型接口实现中，Java 编译器会自动生成合成桥接方法（Bridge Method）。

`RetryMethodResolver` 内部实现了高鲁棒性的元数据解析算法：
1. 向上递归遍历类与实现的全部接口，查找最具体的实际执行方法；
2. 自动还原因泛型擦除导致的桥接方法，精准定位真实业务目标签名；
3. 注解解析优先级：**具体方法 > 接口声明方法 > 具体目标类 > 声明接口/父类**。

---

## 编程式创建重试代理 (`RetryProxyFactory`)

非 Spring 项目可直接使用 `RetryProxyFactory` 快速构建代理对象：

```java
import com.team4u.framework.retry.inline.DefaultInlineRetryClient;
import com.team4u.framework.retry.proxy.RetryProxyFactory;

PaymentService rawService = new PaymentServiceImpl();

// 为接口创建重试代理
PaymentService proxy = RetryProxyFactory.createProxy(
        rawService,
        PaymentService.class,
        DefaultInlineRetryClient.getInstance(), // 进程内客户端
        null                                    // 托管客户端 (可选)
);

proxy.notifyMerchant("ORDER_1001", "{\"amount\": 100}");
```

---

## 托管模式下的快照持久化与恢复回放 (`InvocationReplay`)

当 `@Retryable(mode = RetryMode.MANAGED)` 标注的方法前台重试未完成并转入后台持久化时：

```mermaid
graph TD
    A[调用 proxy.method] --> B[RetryDelegate 拦截]
    B --> C[JacksonRetryContextSerializer 序列化参数快照]
    C --> D[生成 SHA-256 幂等键并落库]
    D --> E[前台执行]
    E -->|前台未完成, 转入后台| F[后台 RetryLeaseWorker 抢占租约]
    F --> G[InvocationReplay 反序列化参数]
    G --> H[从 BeanManager 查找目标 Bean]
    H --> I[RecoveryExecutionContext.run 设置 RECOVERING=true]
    I --> J[反射调用目标方法 target.method]
    J --> K{RetryDelegate 检查 isRecovering?}
    K -->|是 (恢复中)| L[直接放行底层调用, 绝不再触发二次代理重试]
```

### 防递归拦截机制 (`RecoveryExecutionContext`)
- 当后台 Worker 通过 `InvocationReplay` 回放调用目标 Bean 时，会将当前线程上下文标记为 `RecoveryExecutionContext.isRecovering() == true`。
- `RetryDelegate` 在拦截到方法执行前会优先检测此标记：若处于恢复执行中，则**直接放行底层业务调用，不再重复进入代理重试链路**，彻底避免“恢复中再次触发代理”的无限递归死循环。

> [!IMPORTANT]
> **代理 MANAGED 模式限制**：
> - 代理模式下的 MANAGED 方法返回值必须为 `void`。因为任务进入后台异步重试后无法同步返回泛型业务对象。若需即时返回值，请使用编程式 `Retries.managed(...)`。
> - 目标业务类与方法不能被 `final` 关键字修饰。

