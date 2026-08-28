# 托管持久化重试（MANAGED）

MANAGED 把“还要继续重试”这件事记录到任务队列：前台先执行少量次数，失败后不继续拖住用户线程，后台 Worker 从队列取任务继续补偿。

入门只需要记住四个对象：

- `ManagedRetryRuntime`: 组装存储和后台 Worker，应用启动时创建。
- `ManagedRetries.with(...)`: 提交前台动作、恢复 payload 和幂等键（来自 `team4u-retry-managed`）。
- `StringRecoveryHandler`: 后台真正执行补偿动作。
- `ManagedSubmitResult`: 告诉你前台完成了，还是任务已交给后台。

最小可运行示例见[快速开始](quick-start.md#路径二-managed失败后交给后台补偿)。

## 生命周期

一个任务的正常流程：

1. 提交时，框架先按幂等键创建持久化记录。新记录默认给前台保留 5 分钟。
2. 前台执行业务动作。
3. 前台成功：记录改为成功，返回 `Completed(value)`。
4. 前台预算耗尽且仍可重试：写入真实下次执行时间，返回 `Accepted(taskId)`。
5. 后台 Worker 到时间后取任务、执行对应的 `StringRecoveryHandler`。
6. 后台成功则记录成功；仍失败则继续按退避时间重试；次数耗尽或不可重试则记录终态失败。

如果提交时幂等键已存在，前台不会重复建档，返回 `Existing`。

### 尝试次数

- `maxRetries`: 首次执行之后最多再试几次，不包含首次。`2` 表示总共最多执行 3 次；`-1` 表示无限重试。
- `foregroundMaxRetries`: 首次执行之后前台还能额外尝试几次，同样不包含首次。MANAGED 必须显式配置，且不能大于 `maxRetries`。
- attempts 记录前台和后台的总尝试次数，进入后台时不会归零。

例如 `maxRetries=2, foregroundMaxRetries=1`：前台最多执行第 1、2 次；若都失败，后台最多执行第 3 次，然后终态失败。

## 提交结果

| 判定方法 | 结果类 | 含义 |
| :--- | :--- | :--- |
| `isCompleted()` | `Completed` | 前台成功；`getValue()` 返回业务值 |
| `isAccepted()` | `Accepted` | 已持久化并交给后台；可读 `taskId/status/nextAttemptAt` |
| `isExisting()` | `Existing` | 幂等键命中已有任务；不重复执行前台动作 |
| `isFailed()` | `Failed` | 前台失败且策略判定不再重试；`getError()` 返回异常 |
| `isRejected()` | `Rejected` | 存储或移交失败等基础设施拒绝；`getReason()` 返回原因 |

幂等范围是 `queueName + taskType + idempotencyKey`。同一个业务动作在不同 task type 下不会冲突；key 里应包含订单号、事件 ID 等稳定业务标识。

## 运行时配置

```java
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.runtime.lease.ManagedRetryRuntime;

import java.time.Duration;

RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
registry.register(new MerchantNotifyRecoveryHandler());

ManagedRetryRuntime runtime = ManagedRetryRuntime
        .lease(new InMemoryLeaseBackend())
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

常用默认值：

| 配置 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `queueName` | `retry-recovery` | 同一条业务链路使用同一个队列名 |
| `foregroundRecoveryTimeout` | 5 分钟 | 初始任务给前台保留的时间 |
| `lease` | 30 秒 | Worker 持有任务的最长时间；到期可被其他 Worker 接管 |
| `pollInterval` | 250ms | 队列空闲时的检查间隔 |
| `heartbeat` | 开启 | 长时间恢复过程中延长租约；间隔未配置时约为租约的 1/3 |

`heartbeatInterval` 必须大于 0 且小于 `lease`。`workerId` 可以不配，会自动生成。

### 后台处理器

```java
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;

public final class MerchantNotifyRecoveryHandler implements StringRecoveryHandler {

    private final MerchantNotifyService notifyService;

    public MerchantNotifyRecoveryHandler(MerchantNotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @Override
    public String taskName() {
        return "merchant-webhook-notify";
    }

    @Override
    public void recover(String payload, RecoveryContext context) throws Exception {
        notifyService.notify(payload);
    }
}
```

`taskName()` 必须和提交时的 `taskType` 一致。Worker 会按已注册 handler 的 task type 精确取任务，不会处理未知类型。

注册和启动规则：

- 建议创建本地 `RecoveryHandlerRegistry` 并显式注册。
- Worker 启动时会快照 registry 中已有 handler；之后修改 registry 不影响已启动 Worker。
- 也可以在 `build()` 后、`start()` 前调用 `runtime.worker().register(handler)`，该注册只属于这个 Worker。
- 启动前至少要有一个 handler。
- 关闭用 `runtime.close()`、`runtime.shutdown()`，或 `runtime.worker().shutdownGracefully(timeout)`。Worker 关闭后不能重启。

## 部署到多进程

Memory 后端只保存在当前 JVM。生产多进程应使用 JDBC：

```java
import com.team4u.framework.lease.jdbc.JdbcLeaseBackend;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;

import javax.sql.DataSource;

ManagedRetryRuntime runtime = ManagedRetryRuntime
        .lease(new JdbcLeaseBackend(dataSource, new MySqlLeaseDbDialect()))
        .queueName("payment-retry")
        .registry(registry)
        .autoScanRecoveryHandlers(false)
        .defaultPolicy(defaultPolicy)
        .start();
```

MySQL DDL 位于：

```text
team4u-lease/team4u-lease-jdbc/src/main/resources/schema/lease_task_mysql.sql
```

运行 Worker 的每个进程都应注册它能处理的 handler。不同进程可以共享同一个队列，也可以按业务域拆分队列。

## 故障边界

这一节是 MANAGED 与 INLINE 的本质差异，部署前必须理解。

### 前台接管窗口

`foregroundRecoveryTimeout` 默认 5 分钟。它的含义是：

- 新任务创建后，先对后台隐藏这 5 分钟，把机会留给当前前台进程。
- 前台正常移交后台时，任务会立即改按真实退避时间可见。
- 如果进程在创建记录后崩溃、重启或一直没有移交，任务最多 5 分钟后自动对后台可见，不会永远藏起来。
- 如果前台业务执行超过 5 分钟仍未结束，后台可能开始执行同一个任务。因此前台动作和后台 handler 都要能接受“执行超过一次”。

这个窗口必须为正且精确到毫秒。不要用它配置业务退避；真正的重试间隔由 `RetryPolicy.backoff` 决定。

### 至少一次执行

MANAGED 提供的是“至少执行一次”的补偿语义，不是“只会执行一次”。以下情况都可能导致同一个业务动作再次执行：

- 前台执行超时后后台接管。
- Worker 执行完成后，结果写回前崩溃。
- Worker 心跳失败或租约到期，任务被其他进程接管。

所以目标动作必须幂等。对外部系统，建议使用稳定的业务单号或事件 ID；收到重复请求时返回已有结果，而不是重复扣款、重复发货。

### 租约与 fencing

后台 Worker 取到任务后会持有一段租约。租约未到期时，其他 Worker 不会取走；如果 Worker 卡死或失去心跳，租约到期后其他 Worker 可以接管。

接管后旧 Worker 可能还在执行。fencing 的作用是：每个任务版本对应一个新租约凭证，旧凭证的写回会被拒绝，避免旧 Worker 覆盖新 Worker 的结果。它降低写回冲突，但无法阻止旧业务动作已经发生过，所以仍需要幂等。

## 管理状态视图

Lease 队列对外有五个状态：

| 队列状态 | 含义 |
| :--- | :--- |
| `PENDING` | 等待可见时间到达 |
| `RUNNING` | 某个 Worker 正在处理 |
| `SUCCEEDED` | 终态成功 |
| `FAILED` | 终态失败 |
| `CANCELLED` | 终态取消 |

持久化 payload 里的重试记录还保留领域状态：

| 重试状态 | 含义 |
| :--- | :--- |
| `ACCEPTED` | 初始记录，尚未发生前台移交 |
| `WAITING_RETRY` | 等待下次重试时间 |
| `PROCESSING` | 正在执行 |
| `SUCCEEDED` / `FAILED` / `CANCELLED` | 终态 |

查询队列快照时，`RUNNING` 对应重试领域的 `PROCESSING`。

## 持久化格式

`LeaseRetryRecordSerializer` 使用显式 JSON，当前 `version=1`：

- 顶层字段为 `version`、`taskId`、`request`、`state`。
- `request` 保存 task type、幂等键、字符串 payload、策略和创建时间。
- `state` 保存总尝试次数、状态、下次执行时间、最近错误和终态时间。
- 内置退避策略只保存 `type + params`，例如 `exponentialJitter` 保存 `initialDelay`、`multiplier`、`maxDelay`。
- 不写入业务对象 Java 类名，也不反序列化任意对象图。
- 旧版本 Lease payload 没有 `version=1`，不做兼容迁移。

自定义 Backoff 必须提供稳定的 `Backoff.toConfig()`，并在同一个 `BackoffRegistry` 中注册能重建实例的 `BackoffFactory`。无法表达成配置的策略会在序列化时快速失败。

### 自定义异常 allowlist

序列化默认只接受 `java.*` 包下的 Throwable 类名，避免从数据库读取 payload 时加载任意类。`retryOn` 或 `abortOn` 使用自定义异常时，需要显式 allowlist：

```java
import com.team4u.framework.retry.common.backoff.BackoffRegistry;
import com.team4u.framework.retry.runtime.lease.LeaseRetryRecordSerializer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

Set<Class<? extends Throwable>> allowlist =
        new LinkedHashSet<Class<? extends Throwable>>();
allowlist.add(MerchantNotifyException.class);

LeaseRetryRecordSerializer serializer = new LeaseRetryRecordSerializer(
        BackoffRegistry.global(), Collections.unmodifiableSet(allowlist));

ManagedRetryRuntime runtime = ManagedRetryRuntime
        .lease(backend)
        .registry(registry)
        .serializer(serializer)
        .defaultPolicy(defaultPolicy)
        .start();
```

同一个 runtime 的存储和 Worker 必须使用同一个 serializer 实例。allowlist 只影响策略中的异常类名；业务 payload 始终是字符串，编解码由业务 handler 自己负责。

## 基础设施异常

后台执行分两层：

- 业务失败：handler 抛出的业务异常，按 `RetryPolicy` 决定重试或终态失败。
- 基础设施失败：payload 反序列化失败、结果序列化失败、线程中断等。这些会抛出 `RetryInfrastructureException`，它继承 Lease 的 `TaskInfrastructureException`。

基础设施失败不会被写成业务 `FAILED`。Worker 会放弃本次租约写回，任务保持运行中，租约到期后由其他 Worker 接管。这个设计避免把存储或序列化问题误标成业务失败，但也意味着恢复动作必须幂等。
