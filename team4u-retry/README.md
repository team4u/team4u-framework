# team4u-retry

`team4u-retry` 是一个支持进程内重试和持久化托管重试的 Java 重试框架。

它覆盖两类常见场景：

* INLINE：所有重试都在当前进程内完成，适合短链路、同步调用、当前请求必须立即拿到结果的场景。
* MANAGED：先在前台尝试有限次数，失败后把任务持久化并交给后台 Worker 接管，适合补偿、回调、通知、恢复任务这类“必须继续做，但不一定要当前线程一直等”的场景。

项目当前包含 4 个模块：

* `team4u-retry-core`
* `team4u-retry-proxy`
* `team4u-retry-spring`
* `team4u-retry-lease-integration`

---

## 先别看细节，先选用法

第一次接入时，建议先按场景选，而不是从全部能力开始看。

| 你的场景                                 | 推荐方式    | 需要模块                                               |
| ---------------------------------------- | ----------- | ------------------------------------------------------ |
| 给一段同步代码加重试                     | INLINE      | `team4u-retry-core`                                    |
| 给 `CompletableFuture` 加异步重试        | INLINE      | `team4u-retry-core`                                    |
| 想通过注解给方法加重试                   | 代理模式    | `team4u-retry-proxy`                                   |
| Spring 项目里启用注解重试                | Spring 集成 | `team4u-retry-spring`                                  |
| 需要任务持久化、后台接管、进程重启后继续 | MANAGED     | `team4u-retry-core` + `team4u-retry-lease-integration` |

### 什么时候用 INLINE

适合这些场景：

* 第三方 HTTP / RPC 调用
* 数据库或下游服务短时抖动
* 当前线程必须直接拿结果
* 失败后可以直接抛异常给调用方

特点：

* 不持久化
* 没有后台接管
* 最终失败后直接抛出异常
* 不支持配置 `foregroundMaxAttempts`，因为它没有“前台 / 后台拆分”这个概念

### 什么时候用 MANAGED

适合这些场景：

* 支付通知补偿
* 回调补发
* MQ 消费失败后的恢复任务
* 服务重启后仍然希望继续执行的任务

特点：

* 任务先被持久化
* 前台尝试失败后可以转后台继续
* 需要存储、协调器、恢复处理器和 Worker 配合
* 必须显式配置 `foregroundMaxAttempts`
* 必须提供有效的 `RecoverySpec.taskType`

---

## 模块与依赖

### Maven 模块

```xml
<modules>
    <module>team4u-retry-core</module>
    <module>team4u-retry-proxy</module>
    <module>team4u-retry-spring</module>
    <module>team4u-retry-lease-integration</module>
</modules>
```

### 最小依赖组合

#### 只用 INLINE

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-core</artifactId>
    <version>${version}</version>
</dependency>
```

#### 用注解 / 代理模式

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-proxy</artifactId>
    <version>${version}</version>
</dependency>
```

#### Spring 项目启用注解重试

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-spring</artifactId>
    <version>${version}</version>
</dependency>
```

#### 用 MANAGED + Lease 托管

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-core</artifactId>
    <version>${version}</version>
</dependency>

<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-lease-integration</artifactId>
    <version>${version}</version>
</dependency>
```

---

## 核心概念

### `maxRetries`

失败后最多重试多少次，不包含首次执行。

例如 `maxRetries = 2`，表示最多执行 3 次。
`-1` 表示无限重试。

总执行次数恒等于 `1 + maxRetries`。

### `foregroundMaxAttempts`

只在 MANAGED 模式下有意义，表示当前进程内最多同步执行多少次。

约束如下：

* INLINE 模式下不允许设置
* MANAGED 模式下必须显式设置
* 必须大于 0
* 不能大于 `maxRetries + 1`

### 退避策略

框架支持多种退避策略：

* 固定延迟 `fixed`
* 线性递增 `increment`
* 指数退避 `exponential`
* 带抖动的指数退避 `exponentialJitter`

默认退避策略是固定 1000ms。

### 异常匹配规则

`RetryPolicy` 的决策顺序可以理解为：

* 如果达到最大重试次数，不再重试
* 命中 `abortOnExceptions`，立即停止
* 如果配置了 `retryOnExceptions`，但当前异常不在其中，不重试
* 如果配置了 `condition` 且条件不满足，不重试
* 否则允许继续重试

### 包装异常会被自动剥离

框架会自动剥离常见包装异常，例如：

* `CompletionException`
* `ExecutionException`
* `InvocationTargetException`
* `UndeclaredThrowableException`

---

## 5 分钟快速开始

## 推荐写法：统一入口 + 官方 runtime

```java
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.retry.Retries;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.integration.lease.ManagedRetryRuntime;
LeaseBackend backend = ...; // 关于 LeaseBackend 的选择与配置，请参考 [team4u-lease 文档](../team4u-lease/README.md)

ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(backend)
        .defaultPolicy(RetryPolicy.builder()
                .maxRetries(4)
                .foregroundMaxAttempts(2)
                .backoff(Backoffs.fixed(1000))
                .build())
        .start();

ManagedSubmitResult<String> result = Retries.managed(runtime.client())
        .task("pay-notify")
        .idempotentBy("order:1001")
        .payload("{\"orderId\":\"1001\"}")
        .policy(RetryPolicy.builder()
                .maxRetries(4)
                .foregroundMaxAttempts(2)
                .backoff(Backoffs.fixed(1000))
                .build())
        .call(this::notifyPayment);
```

这个写法适合作为默认接入方式：

* `ManagedRetryRuntime.lease(...)` 负责把 MANAGED 运行时组起来
* `Retries` 负责统一 INLINE / MANAGED 的编程入口
* 底层的 `DefaultInlineRetryClient` / `ManagedRetryClient.submit(...)` 也可直接使用，适合高级场景或二次封装，但推荐优先使用 `Retries` 门面类

---

## INLINE：同步重试

```java
import com.team4u.framework.retry.Retries;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.policy.RetryPolicy;

RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(2)
        .backoff(Backoffs.fixed(1000))
        .retryOn(java.io.IOException.class)
        .abortOn(IllegalArgumentException.class)
        .build();

// 使用 Retries 门面类同步执行
String result = Retries.inline()
        .policy(policy)
        .call(this::remoteCall);
```

`Retries.inline()` 提供了一个流式 API，底层依然通过 `DefaultInlineRetryClient` 单例执行。

---

## INLINE：异步重试

```java
import com.team4u.framework.retry.Retries;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.policy.RetryPolicy;

import java.util.concurrent.CompletableFuture;

RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(2)
        .backoff(Backoffs.exponential(200, 2.0, 3000))
        .build();

// 使用 Retries 门面类异步执行
CompletableFuture<String> future = Retries.inline()
        .policy(policy)
        .callAsync(this::asyncRemoteCall);
```

这里的 `asyncRemoteCall` 需要返回 `CompletableFuture<T>`。
框架内置全局调度线程池，通过 `callAsync` 调用时会默认使用该调度器进行退避等待。

---

## MANAGED：前台尝试 + 后台接管

```java
import com.team4u.framework.retry.Retries;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.policy.RetryPolicy;

RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(4)
        .foregroundMaxAttempts(2)
        .backoff(Backoffs.fixed(1000))
        .retryOn(java.io.IOException.class)
        .build();

// 使用 Retries 门面类提交托管任务
ManagedSubmitResult<String> result = Retries.managed(managedRetryClient)
        .task("pay-notify")
        .idempotentBy("order:1001")
        .payload("{\"orderId\":\"1001\"}")
        .policy(policy)
        .call(this::notifyPayment);
```

通过 `Retries.managed(client)` 可以更清晰地链式编排托管任务的各项规格。

---

## MANAGED 托管模式：前台尝试 + 后台接管

MANAGED 模式的核心在于：“任务高可靠持久化” + “执行权可在进程间/线程间流转”。

### 核心模型

当你在 MANAGED 模式下执行一个任务时，它的生命周期如下：

1.  持久化：框架首先将任务规格（Payload、策略、恢复信息）存入 `DurableRetryStore`。
2.  前台尝试：在当前线程中，按 `foregroundMaxAttempts` 指定次数进行同步重试。
3.  结果产出：
    *   Completed: 前台尝试中已经成功了。
    *   Accepted: 前台次数用完还没成功，任务已安全进入后台，正等待 Worker 接管继续重试。
    *   Failed: 命中不可重试异常或已达 `maxRetries` 上限。
    *   Rejected: 参数校验不通过（如缺少持久化必需的 ID 等）。

> [!IMPORTANT]
> `Accepted` 只代表“接管成功”，不代表“业务已成功”。

---

## 官方运行时：`ManagedRetryRuntime`

初始化 MANAGED 模式涉及存储（Store）、协调器（Coordinator）和恢复 Worker 的组合。为了简化接入过程，推荐使用 `ManagedRetryRuntime` 一键配置：

### 1. 一键组装并启动

```java
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.retry.integration.lease.ManagedRetryRuntime;

LeaseBackend backend = ...; // 详见 [team4u-lease 文档](../team4u-lease/README.md)

ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(backend)
        .defaultPolicy(RetryPolicy.builder()
                .maxRetries(9)
                .foregroundMaxAttempts(2)
                .build())
        .start(); 

// 通过 runtime 获取 client 即可开始使用
ManagedRetryClient client = runtime.client();
```

`ManagedRetryRuntime` 会帮你完成以下工作：
*   存储与调度：自动基于 `LeaseBackend` 创建持久化存储和任务调度能力。
*   注册表：管理所有的 `RecoveryHandler`。
*   Worker：启动后台线程，定时拉取属于当前节点的任务进行恢复。

### 2. 定义恢复处理器 (`RecoveryHandler`)

MANAGED 任务进入后台后，框架不知道该调用哪个方法。你需要提供一个 `RecoveryHandler`：

```java
public class PayNotifyHandler implements RecoveryHandler<String> {
    @Override
    public String taskName() {
        return "pay-notify"; // 与提交任务时的 taskType 对应
    }

    @Override
    public void recover(String payload, RecoveryContext context) throws Exception {
        // 后台重试逻辑
    }
}
```

*   自动注册：如果你的 `RecoveryHandler` 在类路径下，`ManagedRetryRuntime` 默认会通过 SPI 自动扫描并注册它。
*   Spring 支持：在 Spring 环境下，只需将 Handler 声明为 `@Bean`，`ManagedRetryRuntime` 会自动发现。

---

## 使用约束与注意事项

为了保证任务能被可靠地持久化和后台恢复，MANAGED 模式有以下强制要求：

1.  必须显式配置 `foregroundMaxAttempts`：不能为 0，且必须小于等于 `maxRetries + 1`。
2.  必须提供幂等键：即 `idempotentBy("...")`，用于去重和状态追踪。
3.  必须提供任务类型：即 `task("...")`，后台 Worker 依赖它找到对应的 `RecoveryHandler`。
4.  建议使用 `Retries` 门面：
    ```java
    Retries.managed(runtime.client())
            .task("pay-notify")
            .idempotentBy("order:1001")
            .payload("...")
            .call(() -> ...);
    ```

---

## 代理 / 注解模式

如果你不想手写 `client.execute(...)`，可以使用 `team4u-retry-proxy` 提供的 `@Retryable`。

### 注解定义

```java
import com.team4u.framework.retry.proxy.RetryMode;
import com.team4u.framework.retry.proxy.Retryable;

public interface PayService {

    @Retryable(policy = "pay-policy", mode = RetryMode.INLINE)
    String notifyPay(String orderId);
}
```

### 代理方式创建

```java
import com.team4u.framework.retry.client.DefaultInlineRetryClient;
import com.team4u.framework.retry.proxy.RetryProxyFactory;

PayService target = new PayServiceImpl();

PayService proxy = RetryProxyFactory.createProxy(
        target,
        PayService.class,
        DefaultInlineRetryClient.getInstance(),
        null
);
```

### 策略注册

`@Retryable(policy = "...")` 依赖策略名查找。
查找顺序是：

1. 先从 `DynamicRetryPolicyRegistry` 查动态配置
2. 查不到再从 `RetryPolicyFactoryRegistry` 查静态注册

示例：

```java
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyFactory;
import com.team4u.framework.retry.policy.RetryPolicyFactoryRegistry;

RetryPolicyFactoryRegistry.global().register(new RetryPolicyFactory() {
    @Override
    public String key() {
        return "pay-policy";
    }

    @Override
    public RetryPolicy create() {
        return RetryPolicy.builder()
                .maxRetries(2)
                .backoff(Backoffs.fixed(100))
                .build();
    }
});
```

---

## Spring 接入

Spring 项目里最简单的启用方式：

```java
import com.team4u.framework.retry.spring.EnableRetry;
import org.springframework.context.annotation.Configuration;

@EnableRetry
@Configuration
public class RetryConfig {
}
```

### `@EnableRetry` 会做什么

启用后会导入 Spring 配置，注册：

* AOP 自动代理创建器
* 默认 `InlineRetryClient`
* `RetryAdvisor`
* 默认 `RecoveryHandler` 扫描注册器
* 生命周期配置，在容器销毁时调用 `RetryExecutorManager.global().shutdown()`

### 最小 Spring 示例

#### 开启自动代理

```java
@Configuration
@EnableRetry
public class RetryConfig {
}
```

#### 注册策略

```java
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyFactory;
import com.team4u.framework.retry.policy.RetryPolicyFactoryRegistry;

RetryPolicyFactoryRegistry.global().register(new RetryPolicyFactory() {
    @Override
    public String key() {
        return "pay-policy";
    }

    @Override
    public RetryPolicy create() {
        return RetryPolicy.builder()
                .maxRetries(2)
                .backoff(Backoffs.fixed(100))
                .build();
    }
});
```

#### 在 Bean 上使用

```java
import com.team4u.framework.retry.proxy.Retryable;
import org.springframework.stereotype.Service;

@Service
public class PayService {

    @Retryable(policy = "pay-policy")
    public String notifyPay(String orderId) {
        return "ok_" + orderId;
    }
}
```

### Spring 中如何接 MANAGED

`@EnableRetry` 不会自动创建 `ManagedRetryClient`。
如果你希望 `@Retryable(mode = RetryMode.MANAGED)` 真正进入托管模型，需要自己声明对应 Bean。

另外需要注意：代理 / 注解模式下的 MANAGED 只支持 `void` 方法。
如果方法需要返回业务结果、`CompletableFuture`，或者你希望拿到提交后的 `taskId/state`，不要复用原业务方法签名，改用编程式 `Retries.managed(managedRetryClient).call(...)`。

如果你更想用统一入口，也可以改用 `Retries.managed(managedRetryClient)...call(...)`。

示例：

```java
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.retry.Retries;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.integration.lease.ManagedRetryRuntime;
import com.team4u.framework.retry.policy.RetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetryManagedConfiguration {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public ManagedRetryRuntime managedRetryRuntime(LeaseBackend backend) { // 关于 backend 注入请参考 team4u-lease 文档
        return ManagedRetryRuntime.lease(backend)
                .defaultPolicy(RetryPolicy.builder()
                        .maxRetries(4)
                        .foregroundMaxAttempts(2)
                        .backoff(Backoffs.fixed(1000))
                        .build())
                .build();
    }

    @Bean
    public ManagedRetryClient managedRetryClient(ManagedRetryRuntime runtime) {
        return runtime.client();
    }

    public String submitNotify(ManagedRetryClient managedRetryClient) {
        return Retries.managed(managedRetryClient)
                .task("pay-notify")
                .idempotentBy("order:1001")
                .payload("{\"orderId\":\"1001\"}")
                .policy(RetryPolicy.builder()
                        .maxRetries(4)
                        .foregroundMaxAttempts(2)
                        .backoff(Backoffs.fixed(1000))
                        .build())
                .call(() -> "ok")
                .isCompleted() ? "ok" : "submitted";
    }
}
```

### 生命周期管理

仅使用 `@EnableRetry` 时，会自动导入 `RetryLifecycleConfiguration`，在 Spring 容器销毁时调用 `RetryExecutorManager.global().shutdown()`。
如果你没有走 `@EnableRetry`，则需要自己显式注册这个生命周期配置，或在应用关闭时手动 shutdown。

### Spring AOP 边界

需要注意：

* 同一个 Bean 内部自调用通常不会经过代理
* `final` 类 / `final` 方法不适合依赖代理增强
* 与 `@Transactional`、日志、监控等多个 Advisor 共存时，顺序取决于代理链

如果要求“自调用也能触发重试”，建议拆分到独立 Bean，或改用编程式接入。

---

## 代理 / 注解模式下的恢复说明

代理模式下，框架会为托管任务构造方法恢复数据，后台通过 `InvocationReplay` 反射调用目标 Bean / 方法完成恢复。

恢复执行阶段会在 lease worker 的统一恢复入口写入 `RecoveryExecutionContext`，避免代理再次进入一轮新的重试包装，防止“恢复时再托管、无限套娃”。

### 参数序列化注意事项

如果方法参数中有这些对象：

* `HttpServletRequest`
* `InputStream`
* 上下文对象
* 超大对象
* 不可序列化对象

可以用 `@RetryIgnore` 标记跳过持久化快照。

限制：

* primitive 参数不能标 `@RetryIgnore`
* 如果 MANAGED 恢复需要重放该参数，就必须保证它可以被完整快照

示例：

```java
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;

public String notifyPay(String orderId, @RetryIgnore InputStream bodyStream) {
    return "ok";
}
```

### 注解模式下的恢复数据

注解式调用进入托管模型时，框架通常会保存调用快照，包括：

* `beanName`
* `methodName`
* `argTypes`
* `argValues`

恢复阶段会基于这些信息重新定位方法并执行补偿调用。

---

## 动态策略配置

如果你希望通过配置中心动态下发重试策略，可以使用 `DynamicRetryPolicyRegistry`。

它默认使用前缀：

```text
retry.policy.
```

例如：

```properties
retry.policy.order-submit={"maxRetries":5,"foregroundMaxAttempts":2,"backoff":{"type":"exponentialJitter","params":{"initialDelay":500,"multiplier":2.0,"maxDelay":10000}},"retryOnExceptions":["java.net.SocketTimeoutException","java.io.IOException"],"abortOnExceptions":["java.lang.IllegalArgumentException"],"condition":"retryCount <= 2"}
```

也就是说，这里的 value 需要是能被 `RetryPolicyFactory.create(String jsonConfig)` 直接解析的合法 JSON 字符串。

可配置字段包括：

* `maxRetries`
* `foregroundMaxAttempts`
* `backoff.type`
* `backoff.params`
* `retryOnExceptions`
* `abortOnExceptions`
* `condition`

---

## 注意事项

### INLINE 不抗进程退出

INLINE 的所有尝试都发生在当前进程里，不做持久化，不会跨进程恢复。

### MANAGED 不是自动幂等

框架会记录 `idempotencyKey`，代理 / 注解模式下也会基于方法快照自动生成稳定 key，但业务是否真的幂等，仍然需要接入方自己保证。

### MANAGED 不等于“只要提交就一定成功”

它只保证任务可被可靠接收并调度，最终是否成功还取决于：

* 恢复处理器是否正确注册
* Worker 是否启动
* 任务本身是否仍然满足重试条件

### `Error` 不会进入重试

无论是 `INLINE` 还是 `MANAGED`，像 `OutOfMemoryError` 这类 `Error` 都会直接 fail-fast，不进入重试循环。

### 非 Spring 场景要自己关注线程池生命周期

框架提供了全局线程池和 shutdown hook，但在独立运行环境里，仍建议你显式管理资源关闭。

### `CompletableFuture` 之外的异步返回值不会自动走异步重试分支

代理/注解模式里，当前只对返回类型是 `CompletableFuture` 的方法走异步重试逻辑。其他返回类型仍按同步路径处理。

### 代理 / 注解模式下，`CompletableFuture` 异步重试仅支持 INLINE

`@Retryable(mode = RetryMode.MANAGED)` 在代理 / Spring AOP 接入下只支持 `void` 方法。

这意味着：

* `CompletableFuture<T>` 返回值不能和 MANAGED 一起使用
* 其他任何非 `void` 返回值也不能和 MANAGED 一起使用
* 如果你需要异步结果或业务返回值，改用 `INLINE`
* 如果你需要提交结果 / 任务元数据，改用编程式 `Retries.managed(managedRetryClient).call(...)`

---

## FAQ

### `maxRetries = 2` 表示什么？

表示失败后最多重试 2 次，因此总共最多执行 3 次。

### 为什么 INLINE 不支持 `foregroundMaxAttempts`？

因为 INLINE 没有后台托管概念，不存在“前台尝试几次再交给后台”。

### 为什么我初始化了 `managedRetryClient`，任务还是没继续执行？

大概率是缺了下面某一项：

* 没有注册对应的 `RecoveryHandler`
* 没有启动 `RetryLeaseWorker`
* `RecoverySpec.taskType` 和 handler 的 `taskName()` 没对上
* 你的 lease backend 其实不具备 `LeaseRuntimeClient` 运行时能力

### `Accepted` 和 `Completed` 的区别是什么？

* `Completed`：前台已经执行成功
* `Accepted`：任务已经被后台接收，但还没最终完成

### MANAGED 最小接入集是什么？

最小建议组合：

* `LeaseBackend`
* `LeaseDurableRetryStore`
* `RecoveryHandlerRegistry`
* `DefaultManagedRetryClient`
* `RetryLeaseWorker`

---

## 推荐的首次接入顺序

建议第一次接入按这个顺序走：

* 先用 INLINE 验证 `RetryPolicy` 是否符合预期
* 再引入 lease 集成，通过 `Retries.managed(client)` 验证托管链路
* 写一个最简单的 `RecoveryHandler`
* 启动 `RetryLeaseWorker`
* 最后再接注解 / Spring 自动化
