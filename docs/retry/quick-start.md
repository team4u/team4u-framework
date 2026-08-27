# 快速开始

本文介绍 `team4u-retry` 的两种核心接入方式：**进程内即时重试 (INLINE)** 与 **托管持久化重试 (MANAGED)**。

## 引入依赖

### 仅使用 INLINE 模式

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 使用 MANAGED 托管持久化模式

`team4u-retry-lease-runtime` 提供 `ManagedRetryRuntime`。`team4u-lease-memory` 适合单进程测试和演示；生产多进程接管请使用 `team4u-lease-jdbc`。

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-lease-runtime</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-memory</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## INLINE 同步重试

```java
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;

import java.io.IOException;

// maxRetries 不包含首次执行：总共最多执行 3 次。
RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(2)
        .backoff(Backoffs.fixed(1000))
        .retryOn(IOException.class)
        .abortOn(IllegalArgumentException.class)
        .build();

String result = Retries.inline()
        .policy(policy)
        .call(() -> remoteHttpService.call("params"));

System.out.println("执行结果: " + result);
```

## INLINE 异步重试

```java
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;

import java.util.concurrent.CompletableFuture;

RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(3)
        .backoff(Backoffs.exponentialJitter(200, 2.0, 3000))
        .build();

CompletableFuture<String> future = Retries.inline()
        .policy(policy)
        .callAsync(() -> asyncHttpService.callAsync("params"));

future.thenAccept(result -> System.out.println("异步结果: " + result));
```

## MANAGED 托管持久化重试

MANAGED 的核心约定：

- `maxRetries` 不包含首次执行，总尝试上限是 `maxRetries + 1`。
- `foregroundMaxRetries` 同样不包含首次执行，且必须显式配置，不能超过 `maxRetries`。
- 前台与后台共享同一个持久化 `attempts` 计数，后台会从前台失败次数之后继续。
- 交付边界是 **at-least-once**：进程崩溃、前台接管超时或租约接管都可能导致恢复逻辑再次执行，业务恢复处理器必须幂等。

### 组装并启动运行时

下面的例子使用进程内 Memory 后端。生产环境请将 `InMemoryLeaseBackend` 换成 `new JdbcLeaseBackend(dataSource)` 或 `new JdbcLeaseBackend(dataSource, dialect)`。

```java
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import com.team4u.framework.retry.runtime.lease.ManagedRetryRuntime;

import java.time.Duration;

class PayNotifyRecoveryHandler implements StringRecoveryHandler {
    private final PaymentNotifyService paymentNotifyService;

    PayNotifyRecoveryHandler(PaymentNotifyService paymentNotifyService) {
        this.paymentNotifyService = paymentNotifyService;
    }

    @Override
    public String taskName() {
        return "pay-notify";
    }

    @Override
    public void recover(String payload, RecoveryContext context) throws Exception {
        paymentNotifyService.notify(payload);
    }
}

LeaseBackend backend = new InMemoryLeaseBackend();
RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
registry.register(new PayNotifyRecoveryHandler(paymentNotifyService));

ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(backend)
        .queueName("payment-retry")
        .registry(registry)
        .autoScanRecoveryHandlers(false)
        .defaultPolicy(RetryPolicy.builder()
                .maxRetries(5)
                .foregroundMaxRetries(1)
                .backoff(Backoffs.exponentialJitter(1000, 2.0, 60_000L))
                .build())
        .foregroundRecoveryTimeout(Duration.ofMinutes(5))
        .workerId("payment-retry-worker-1")
        .lease(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(250))
        .heartbeatEnabled(true)
        .heartbeatInterval(Duration.ofSeconds(10))
        .threadName("payment-retry-worker")
        .start();
```

`ManagedRetryRuntime` 默认使用 `retry-recovery` 队列、30 秒租约、250ms 轮询、开启心跳、5 分钟前台接管窗口。业务恢复处理器必须实现 `com.team4u.framework.retry.managed.recovery.StringRecoveryHandler`。推荐像上例一样使用本地 registry 显式注册；`autoScanRecoveryHandlers(true)` 只会向该 runtime 的本地 registry 做 ServiceLoader 扫描，不会修改全局 registry。

### JDBC 后端

```java
import com.team4u.framework.lease.jdbc.JdbcLeaseBackend;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;
import com.team4u.framework.lease.spi.LeaseBackend;

import javax.sql.DataSource;

LeaseBackend backend = new JdbcLeaseBackend(dataSource, new MySqlLeaseDbDialect());
ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(backend)
        // 其余配置与 Memory 示例相同
        .start();
```

### 提交任务

```java
import com.team4u.framework.retry.api.ManagedSubmitResult;
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;

ManagedSubmitResult<String> result = Retries.managed(runtime.client())
        .taskType("pay-notify")
        .idempotencyKey("order_998811")
        .payload("{\"orderId\":\"order_998811\"}")
        .policy(RetryPolicy.builder()
                .maxRetries(5)
                .foregroundMaxRetries(1)
                .backoff(Backoffs.fixed(1000))
                .build())
        .call(() -> paymentNotifyService.notify("{\"orderId\":\"order_998811\"}"));

if (result.isCompleted()) {
    ManagedSubmitResult.Completed<String> completed =
            (ManagedSubmitResult.Completed<String>) result;
    System.out.println("前台完成: " + completed.getValue());
} else if (result.isAccepted()) {
    ManagedSubmitResult.Accepted<String> accepted =
            (ManagedSubmitResult.Accepted<String>) result;
    System.out.println("已交由后台接管, taskId=" + accepted.getTaskId());
} else if (result.isExisting()) {
    ManagedSubmitResult.Existing<String> existing =
            (ManagedSubmitResult.Existing<String>) result;
    System.out.println("命中已有任务, taskId=" + existing.getTaskId());
} else if (result.isFailed()) {
    Throwable error = ((ManagedSubmitResult.Failed<String>) result).getError();
    System.err.println("终态失败: " + error.getMessage());
}
```

## 下一步

- 深入掌握进程内重试与异常拆包机制：[进程内重试 (INLINE)](retry-inline.md)
- 了解前后台分级、持久化格式与分布式恢复：[托管持久化重试 (MANAGED)](retry-managed.md)
- 查看退避算法与动态配置下发：[退避策略与动态配置](retry-strategy.md)
- 开启 `@Retryable` 注解与 Spring 整合：[注解与代理模式](retry-proxy.md) · [Spring 整合](retry-spring.md)
- 查阅生产级实战案例：[实战案例](retry-sample.md)
