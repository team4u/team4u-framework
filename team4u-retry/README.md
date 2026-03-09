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
* 不支持配置 `foregroundAttempts`，因为它没有“前台 / 后台拆分”这个概念

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
* 必须显式配置 `foregroundAttempts`
* 必须提供有效的 `RecoverySpec.taskName`

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

### `maxAttempts`

最大尝试次数，包含首次执行。

例如 `maxAttempts = 3`，表示最多执行 3 次，不是“失败后再重试 3 次”。
`-1` 表示无限重试。

### `foregroundAttempts`

只在 MANAGED 模式下有意义，表示当前进程内最多同步尝试多少次。

约束如下：

* INLINE 模式下不允许设置
* MANAGED 模式下必须显式设置
* 必须大于 0
* 不能大于 `maxAttempts`

### 退避策略

框架支持多种退避策略：

* 固定延迟 `fixed`
* 线性递增 `increment`
* 指数退避 `exponential`
* 带抖动的指数退避 `exponentialJitter`

默认退避策略是固定 1000ms。

### 异常匹配规则

`RetryPolicy` 的决策顺序可以理解为：

* 如果达到最大尝试次数，不再重试
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

## INLINE：同步重试

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

`DefaultInlineRetryClient` 是开箱即用的单例入口。

---

## INLINE：异步重试

```java
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultInlineRetryClient;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.policy.RetryPolicy;

import java.util.concurrent.CompletableFuture;

RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)
        .backoff(Backoffs.exponential(200, 2.0, 3000))
        .build();

CompletableFuture<String> future = DefaultInlineRetryClient.getInstance()
        .executeAsync(
                policy,
                this::asyncRemoteCall,
                RetryExecutorManager.global().getScheduler()
        );
```

这里的 `asyncRemoteCall` 需要返回 `CompletableFuture<T>`。
框架内置全局调度线程池；在 Spring 环境下会自动关闭，非 Spring 环境下建议自行关注生命周期。

---

## MANAGED：前台尝试 + 后台接管

```java
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RecoverySpec;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.policy.RetryPolicy;

RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(5)
        .foregroundAttempts(2)
        .backoff(Backoffs.fixed(1000))
        .retryOn(java.io.IOException.class)
        .build();

RetryTaskSpec<String> spec = RetryTaskSpec.<String>builder()
        .taskName("pay-notify")
        .idempotencyKey("order:1001")
        .executor(() -> notifyPayment())
        .recovery(RecoverySpec.of("pay-notify", "{\"orderId\":\"1001\"}"))
        .policy(policy)
        .build();

ManagedSubmitResult<String> result = managedRetryClient.submit(spec);
```

`RetryTaskSpec` 至少包括：

* `taskName`
* `idempotencyKey`
* `executor`
* `recovery`
* `policy`

---

## MANAGED 到底是什么

MANAGED 模式不是“把 INLINE 再包一层”，而是一套单独的运行模型。

提交流程大致是：

```text
提交 RetryTaskSpec
    ↓
校验 policy / recovery
    ↓
创建持久化 RetryRecord（PREPARED）
    ↓
前台按 foregroundAttempts 尝试执行
    ↓
成功 -> Completed
不可再试 -> Failed
还能再试但前台预算耗尽 -> Accepted，并交给 coordinator 调度后台
```

### `ManagedSubmitResult` 的含义

MANAGED 提交会返回 4 种终态之一：

* `Completed<T>`：前台已经成功完成
* `Accepted<T>`：任务已被可靠接收，后续会由后台继续
* `Rejected<T>`：任务未被接收，例如配置不合法
* `Failed<T>`：明确失败，不再继续重试

### 一个容易误解的点

`Accepted` 不代表业务已经成功，只代表：

* 任务已经持久化
* 当前前台阶段已经结束
* 后续会由后台 Worker 按策略继续执行

---

## `managedRetryClient` 怎么初始化

这是第一次接入最容易卡住的地方。

`managedRetryClient` 不是像 `DefaultInlineRetryClient.getInstance()` 那样直接拿来就能用。
它要求你自己把依赖组起来：

* `DurableRetryStore`
* `RecoveryHandlerRegistry`
* `RetryCoordinator`
* 可选的默认 `RetryPolicy`

如果缺任何一个，构造时会直接失败。

### 最推荐的初始化方式：基于 lease

`team4u-retry-lease-integration` 已经提供了最省事的一套实现：

* `LeaseDurableRetryStore` 同时实现了 `DurableRetryStore` 和 `RetryCoordinator`
* `RetryLeaseWorker` 负责后台消费和恢复执行

### 最小初始化代码

```java
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultManagedRetryClient;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.integration.lease.LeaseDurableRetryStore;
import com.team4u.framework.retry.integration.lease.RetryLeaseWorker;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;

LeaseBackend backend = ...;

// store + coordinator
LeaseDurableRetryStore store = new LeaseDurableRetryStore(backend);

// recovery registry
RecoveryHandlerRegistry registry = RecoveryHandlerRegistry.global();

// default policy（可选）
RetryPolicy defaultPolicy = RetryPolicy.builder()
        .maxAttempts(5)
        .foregroundAttempts(2)
        .backoff(Backoffs.fixed(1000))
        .build();

// managed client
ManagedRetryClient managedRetryClient = DefaultManagedRetryClient.builder()
        .store(store)
        .coordinator(store)
        .defaultPolicy(defaultPolicy)
        .build();

// worker（必须有后台执行者）
RetryLeaseWorker worker = new RetryLeaseWorker((LeaseRuntimeClient) backend, store, registry);
worker.start();
```

### 初始化时要特别注意

上面这段代码隐含一个前提：
你的 `backend` 不仅要能作为 `LeaseBackend` 使用，还要能作为 `LeaseRuntimeClient` 使用，否则 `RetryLeaseWorker` 无法启动。

也就是说，在实际接入里，可运行的 lease backend 需要同时覆盖：

* 发布 / 管理任务能力
* Worker 运行时消费能力

---

## MANAGED 最小可运行链路

如果你只记一件事，就记这条链路：

```text
LeaseBackend
   ↓
LeaseDurableRetryStore
   ├─ 作为 DurableRetryStore
   └─ 作为 RetryCoordinator

RecoveryHandlerRegistry
   ↓
DefaultManagedRetryClient
   ↓
RetryLeaseWorker
   ↓
RecoveryHandler
```

其中：

### `DurableRetryStore`

负责“把任务记下来”，包括：

* `create`
* `markRunning`
* `scheduleNext`
* `markSucceeded`
* `markFailed`
* `cancel`
* `get`

### `RetryCoordinator`

负责“告诉后台系统什么时候执行这个任务”。

核心接口：

```java
void schedule(RetryRecord record, long delayMillis)
```

### `RecoveryHandlerRegistry`

负责“恢复时到底调用谁”。

它内部以 `taskName` 为 key 管理 `RecoveryHandler`，支持：

* 全局单例 `RecoveryHandlerRegistry.global()`
* SPI 自动扫描
* Spring 启动时的默认扫描注册

### `RetryLeaseWorker`

负责真正从队列取任务并执行恢复逻辑。
没有 Worker，MANAGED 任务即使被 `Accepted`，也不会有人继续跑。

---

## 恢复处理器怎么写

MANAGED 后台恢复依赖 `RecoveryHandler`，它的 key 就是 `taskName()`。

### 示例：支付通知补偿

```java
import com.team4u.framework.retry.recovery.RecoveryContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;

public class PayNotifyRecoveryHandler implements RecoveryHandler<String> {

    @Override
    public String taskName() {
        return "pay-notify";
    }

    @Override
    public void recover(String payload, RecoveryContext context) throws Exception {
        doNotify(payload);
    }

    private void doNotify(String payload) {
        // 真实补偿逻辑
    }
}
```

### 对应提交时要这样写

```java
RetryTaskSpec<String> spec = RetryTaskSpec.<String>builder()
        .taskName("pay-notify")
        .idempotencyKey("order:1001")
        .executor(() -> notifyPayment())
        .recovery(RecoverySpec.of("pay-notify", "{\"orderId\":\"1001\"}"))
        .policy(policy)
        .build();
```

这里有两个 key 必须对上：

* `RecoveryHandler.taskName()`
* `RecoverySpec.taskName`

---

## MANAGED 的几个关键约束

### 必须显式配置 `foregroundAttempts`

MANAGED 下如果策略没有显式设置 `foregroundAttempts`，提交会直接 `Rejected`。

### 必须提供有效的 `RecoverySpec.taskName`

如果没有 `recovery`，或者 `taskName` 为 `null`，也会被 `Rejected`。

### `foregroundAttempts` 不能大于 `maxAttempts`

这个约束在构建 `RetryPolicy` 时就会校验。

### `Accepted` 不代表成功

它只表示任务已进入后台调度，不表示业务已经完成。

---

## 代理 / 注解模式

如果你不想手写 `client.execute(...)`，可以使用 `team4u-retry-proxy` 提供的 `@Retryable`。

### 注解定义

```java
import com.team4u.framework.retry.RetryMode;
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
                .maxAttempts(3)
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
                .maxAttempts(3)
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

示例：

```java
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultManagedRetryClient;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.integration.lease.LeaseDurableRetryStore;
import com.team4u.framework.retry.integration.lease.RetryLeaseWorker;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetryManagedConfiguration {

    @Bean
    public LeaseDurableRetryStore leaseRetryStore(LeaseBackend backend) {
        return new LeaseDurableRetryStore(backend);
    }

    @Bean
    public ManagedRetryClient managedRetryClient(LeaseDurableRetryStore store) {
        RetryPolicy defaultPolicy = RetryPolicy.builder()
                .maxAttempts(5)
                .foregroundAttempts(2)
                .backoff(Backoffs.fixed(1000))
                .build();

        return DefaultManagedRetryClient.builder()
                .store(store)
                .coordinator(store)
                .defaultPolicy(defaultPolicy)
                .build();
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public RetryLeaseWorker retryLeaseWorker(
            LeaseBackend backend,
            LeaseDurableRetryStore store) {
        return new RetryLeaseWorker(
                (LeaseRuntimeClient) backend,
                store,
                RecoveryHandlerRegistry.global()
        );
    }
}
```

### 生命周期管理

`team4u-retry-spring` 已内置生命周期配置，会在 Spring 容器销毁时调用 `RetryExecutorManager.global().shutdown()`。

### Spring AOP 边界

需要注意：

* 同一个 Bean 内部自调用通常不会经过代理
* `final` 类 / `final` 方法不适合依赖代理增强
* 与 `@Transactional`、日志、监控等多个 Advisor 共存时，顺序取决于代理链

如果要求“自调用也能触发重试”，建议拆分到独立 Bean，或改用编程式接入。

---

## 代理 / 注解模式下的恢复说明

代理模式下，框架会为托管任务构造方法恢复数据，后台通过 `InvocationReplay` 反射调用目标 Bean / 方法完成恢复。

恢复执行阶段会通过 `RecoveryExecutionContext` 标识当前线程处于恢复上下文，避免代理再次进入一轮新的重试包装，防止“恢复时再托管、无限套娃”。

### 参数序列化注意事项

如果方法参数中有这些对象：

* `HttpServletRequest`
* `InputStream`
* 上下文对象
* 超大对象
* 不可序列化对象

可以用 `@RetryIgnore` 标记跳过持久化快照。

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
retry.policy.order-submit={
  "maxAttempts": 6,
  "localAttempts": 2,
  "backoff": {
    "type": "exponentialJitter",
    "params": {
      "initialDelay": 500,
      "multiplier": 2.0,
      "maxDelay": 10000
    }
  },
  "retryOnExceptions": ["java.net.SocketTimeoutException", "java.io.IOException"],
  "abortOnExceptions": ["java.lang.IllegalArgumentException"],
  "condition": ""
}
```

> 兼容说明：当前配置模型里字段名仍为 `localAttempts`，工厂在构建 `RetryPolicy` 时会映射到 `foregroundAttempts`。

可配置字段包括：

* `maxAttempts`
* `localAttempts`
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

框架会记录 `idempotencyKey`，但业务是否真的幂等，仍然需要接入方自己保证。

### MANAGED 不等于“只要提交就一定成功”

它只保证任务可被可靠接收并调度，最终是否成功还取决于：

* 恢复处理器是否正确注册
* Worker 是否启动
* 任务本身是否仍然满足重试条件

### 非 Spring 场景要自己关注线程池生命周期

框架提供了全局线程池和 shutdown hook，但在独立运行环境里，仍建议你显式管理资源关闭。

### `Error` 不会进入普通重试逻辑

像 `OutOfMemoryError` 这种严重错误会直接抛出，不参与正常重试循环。

### `CompletableFuture` 之外的异步返回值不会自动走异步重试分支

代理/注解模式里，当前只对返回类型是 `CompletableFuture` 的方法走异步重试逻辑。其他返回类型仍按同步路径处理。

---

## FAQ

### `maxAttempts = 3` 表示什么？

表示最多执行 3 次，包含首次执行。

### 为什么 INLINE 不支持 `foregroundAttempts`？

因为 INLINE 没有后台托管概念，不存在“前台尝试几次再交给后台”。

### 为什么我初始化了 `managedRetryClient`，任务还是没继续执行？

大概率是缺了下面某一项：

* 没有注册对应的 `RecoveryHandler`
* 没有启动 `RetryLeaseWorker`
* `RecoverySpec.taskName` 和 handler 的 `taskName()` 没对上
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
* 再引入 lease 集成，初始化 `managedRetryClient`
* 写一个最简单的 `RecoveryHandler`
* 启动 `RetryLeaseWorker`
* 最后再接注解 / Spring 自动化