# team4u-retry

`team4u-retry` 是一个支持进程内重试和持久化托管重试的 Java 重试框架。

它覆盖两类常见场景：

* **INLINE**：所有重试都在当前进程内完成，适合短链路、同步调用、当前请求必须立即拿到结果的场景。
* **MANAGED**：先在前台尝试有限次数，失败后把任务持久化并交给后台 Worker 接管，适合补偿、回调、通知、恢复任务这类“必须继续做，但不一定要当前线程一直等”的场景。

项目当前包含 4 个模块：

* `team4u-retry-core`：核心逻辑、策略定义及基础客户端实现。
* `team4u-retry-proxy`：基于代理和注解的重试支持，包含方法调用快照恢复。
* `team4u-retry-spring`：Spring 框架集成，支持 AOP 自动代理。
* `team4u-retry-lease-integration`：基于 `team4u-lease` 的持久化与分布式调度集成。

---

## 场景选择指南

第一次接入时，建议根据业务场景选择合适的重试模式。

| 你的场景 | 推荐方式 | 需要模块 |
| :--- | :--- | :--- |
| 给一段同步代码加重试 | INLINE | `team4u-retry-core` |
| 给 `CompletableFuture` 加异步重试 | INLINE | `team4u-retry-core` |
| 想通过注解给方法加重试 | 代理模式 | `team4u-retry-proxy` |
| Spring 项目里启用注解重试 | Spring 集成 | `team4u-retry-spring` |
| 需要任务持久化、后台接管、进程重启后继续 | MANAGED | `team4u-retry-core` + `team4u-retry-lease-integration` |

### 什么时候用 INLINE

* 第三方 HTTP / RPC 调用。
* 数据库或下游服务短时抖动。
* 当前线程必须直接获取结果。
* 失败后可以直接抛异常给调用方。
* **特点**：不持久化，无后台接管，最终失败抛出异常，不支持 `foregroundAttempts`。

### 什么时候用 MANAGED

* 支付通知补偿。
* 回调补发。
* MQ 消费失败后的恢复任务。
* 服务重启后仍需继续执行的任务。
* **特点**：任务先持久化，前台尝试失败转后台，需要 Worker 配合，必须配置 `foregroundAttempts` 和 `RecoverySpec.taskType`。

---

## 核心概念

### `maxAttempts`
最大尝试次数，包含首次执行。例如 `maxAttempts = 3` 表示最多执行 3 次。`-1` 表示无限重试。

### `foregroundAttempts`
仅在 MANAGED 模式下有效，表示当前进程内最多同步尝试多少次。必须大于 0 且不大于 `maxAttempts`。

### 退避策略 (Backoff)
支持多种策略：固定延迟 (`fixed`)、线性递增 (`increment`)、指数退避 (`exponential`)、带抖动的指数退避 (`exponentialJitter`)。默认固定 1000ms。

### 异常匹配与条件
重试决策顺序：
1. 达到 `maxAttempts` 则停止。
2. 命中 `abortOnExceptions` 则停止。
3. 若配置了 `retryOnExceptions` 但当前异常不匹配则停止。
4. 若配置了 `condition` 表达式且结果为 `false` 则停止。

#### 条件表达式变量
在 `condition` 表达式中，可以使用以下变量：
* `attempt`：当前尝试次数（从 1 开始）。
* `maxAttempts`：最大尝试次数。
* `cause`：当前异常对象。
* `message`：当前异常的消息文本。

### 包装异常自动剥离
框架会自动剥离 `CompletionException`, `ExecutionException`, `InvocationTargetException` 和 `UndeclaredThrowableException` 以获取真实的业务异常。

---

## 快速开始

### INLINE：同步重试

```java
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultInlineRetryClient;
import com.team4u.framework.retry.policy.RetryPolicy;

RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)
        .backoff(Backoffs.fixed(1000))
        .retryOn(java.io.IOException.class)
        .abortOn(IllegalArgumentException.class)
        .build();

String result = DefaultInlineRetryClient.getInstance()
        .execute(policy, this::remoteCall);
```

### INLINE：异步重试

```java
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultInlineRetryClient;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.policy.RetryPolicy;

RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)
        .backoff(Backoffs.exponential(200, 2.0, 3000))
        .build();

// asyncRemoteCall 需返回 CompletableFuture<T>
CompletableFuture<String> future = DefaultInlineRetryClient.getInstance()
        .executeAsync(
                policy,
                this::asyncRemoteCall,
                RetryExecutorManager.global().getScheduler()
        );
```

### MANAGED：托管重试

```java
RetryTaskSpec<String> spec = RetryTaskSpec.<String>builder()
        .idempotencyKey("order:1001")
        .executor(() -> notifyPayment())
        .recovery(RecoverySpec.of("pay-notify", "{\"orderId\":\"1001\"}"))
        .policy(policy)
        .build();

ManagedSubmitResult<String> result = managedRetryClient.submit(spec);
```

---

## 代理与注解模式

使用 `@Retryable` 注解简化接入。

```java
public interface PayService {
    @Retryable(policy = "pay-policy", mode = RetryMode.INLINE)
    String notifyPay(String orderId);
}
```

### 策略注册

```java
RetryPolicyFactoryRegistry.global().register(new RetryPolicyFactory() {
    @Override
    public String key() { return "pay-policy"; }

    @Override
    public RetryPolicy create() {
        return RetryPolicy.builder()
                .maxAttempts(3)
                .backoff(Backoffs.fixed(100))
                .build();
    }
});
```

---

## Spring 接入

在配置类上添加 `@EnableRetry` 即可启用：

```java
@Configuration
@EnableRetry
public class RetryConfig {
}
```

### 恢复处理器
MANAGED 模式下的后台恢复依赖 `RecoveryHandler`。框架会自动扫描并通过 SPI 或 Spring Bean 注册。代理模式默认使用 `InvocationReplay` 进行方法重放恢复。

---

## 动态策略配置

支持通过配置中心动态下发重试策略，默认前缀为 `retry.policy.`。

```properties
retry.policy.order-submit={
  "maxAttempts": 6,
  "foregroundAttempts": 2,
  "backoff": {
    "type": "exponentialjitter",
    "params": {
      "initialDelay": 500,
      "multiplier": 2.0,
      "maxDelay": 10000
    }
  },
  "retryOnExceptions": ["java.net.SocketTimeoutException", "java.io.IOException"],
  "abortOnExceptions": ["java.lang.IllegalArgumentException"],
  "condition": "attempt <= 3"
}
```

---

## 注意事项

* **Error 不重试**：`OutOfMemoryError` 等严重错误会触发 fail-fast，不进入重试。
* **MANAGED 约束**：
    * 必须显式配置 `foregroundAttempts`。
    * 在代理模式下仅支持 `void` 方法。
    * 需确保注册了对应的 `RecoveryHandler` 并启动了 Worker。
* **参数忽略**：在 MANAGED 模式下，无法持久化的参数（如 `InputStream`）可用 `@RetryIgnore` 标记，但该注解不能用于基本类型（primitive）。
* **线程池管理**：框架内置全局线程池 `RetryExecutorManager`，Spring 环境下会自动优雅关闭，非 Spring 环境建议手动调用 `shutdown()`。