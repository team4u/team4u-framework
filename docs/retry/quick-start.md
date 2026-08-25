# 快速开始

本文介绍 `team4u-retry` 的两种核心接入方式：**进程内即时重试 (INLINE)** 与 **官方托管重试 (MANAGED)**。

---

## 1. 引入依赖

### 仅使用 INLINE 模式
```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 使用 MANAGED 托管持久化模式
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
    <artifactId>team4u-lease-jdbc</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## 2. INLINE 同步重试快速上手

```java
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import java.io.IOException;

// 1. 构建重试策略：最多重试 2 次（总执行 3 次），固定间隔 1000ms，仅在 IOException 时重试
RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(2)
        .backoff(Backoffs.fixed(1000))
        .retryOn(IOException.class)
        .abortOn(IllegalArgumentException.class)
        .build();

// 2. 流式调用目标方法（在当前线程中同步执行）
String result = Retries.inline()
        .policy(policy)
        .call(() -> remoteHttpService.call("params"));

System.out.println("执行结果: " + result);
```

---

## 3. INLINE 异步重试 (`CompletableFuture`)

```java
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import java.util.concurrent.CompletableFuture;

RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(3)
        .backoff(Backoffs.exponentialJitter(200, 2.0, 3000)) // 指数退避加抖动防风暴
        .build();

// 异步执行：内部使用内置调度线程池实现非阻塞延迟重试，全程不占用业务工作线程
CompletableFuture<String> future = Retries.inline()
        .policy(policy)
        .callAsync(() -> asyncHttpService.callAsync("params"));

future.thenAccept(res -> System.out.println("异步结果: " + res));
```

---

## 4. MANAGED 托管持久化重试快速上手

适用于前台尝试有限次数，若失败则持久化并由后台 Worker 持续接管补偿的场景：

### 1. 组装并启动运行时
```java
import com.team4u.framework.lease.api.LeaseBackend;
import com.team4u.framework.retry.api.ManagedSubmitResult;
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.runtime.lease.ManagedRetryRuntime;
import com.team4u.framework.retry.runtime.lease.StringRecoveryHandler;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;

LeaseBackend backend = ...; // 详见 team4u-lease 组件配置

// 启动托管运行时并注册后台恢复处理器
ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(backend)
        .defaultPolicy(RetryPolicy.builder()
                .maxRetries(5)
                .foregroundMaxRetries(1) // 前台最多重试 1 次（总尝试 2 次）
                .backoff(Backoffs.exponentialJitter(1000, 2.0, 60_000L))
                .build())
        .register(new StringRecoveryHandler() {
            @Override
            public String taskName() {
                return "pay-notify"; // 与提交任务的 taskType 匹配
            }

            @Override
            public void recover(String payload, RecoveryContext context) throws Exception {
                // 后台 Worker 接管后的恢复重放逻辑
                paymentNotifyService.notify(payload);
            }
        })
        .start();
```

### 2. 提交任务
```java
ManagedSubmitResult<String> result = Retries.managed(runtime.client())
        .taskType("pay-notify")
        .idempotencyKey("order_998811") // 业务幂等键
        .payload("{\"orderId\":\"order_998811\"}")
        .policy(RetryPolicy.builder()
                .maxRetries(5)
                .foregroundMaxRetries(1)
                .backoff(Backoffs.fixed(1000))
                .build())
        .call(() -> paymentNotifyService.notify("{\"orderId\":\"order_998811\"}"));

if (result.isCompleted()) {
    ManagedSubmitResult.Completed<String> completed = (ManagedSubmitResult.Completed<String>) result;
    System.out.println("前台即时完成, 返回值: " + completed.getValue());
} else if (result.isAccepted()) {
    ManagedSubmitResult.Accepted<String> accepted = (ManagedSubmitResult.Accepted<String>) result;
    System.out.println("前台尝试用尽，任务已持久化并交由后台接管, taskId=" + accepted.getTaskId());
} else if (result.isExisting()) {
    System.out.println("命中已有幂等记录");
}
```

---

## 下一步

- 深入掌握进程内重试与异常拆包机制：[进程内重试 (INLINE)](retry-inline.md)
- 了解前后台分级与分布式 Worker 恢复：[托管持久化重试 (MANAGED)](retry-managed.md)
- 查看退避算法与动态配置下发：[退避策略与动态配置](retry-strategy.md)
- 开启 `@Retryable` 注解与 Spring 整合：[注解与代理模式](retry-proxy.md) · [Spring 整合](retry-spring.md)
- 查阅生产级实战案例：[实战案例](retry-sample.md)

