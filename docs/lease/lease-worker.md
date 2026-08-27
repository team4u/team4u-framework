# Worker 处理

业务代码只需要写 handler：拿到 `TaskContext`，处理任务，返回 `TaskResult`。抢占任务、延长执行权、写回状态这些事情由 `TaskWorker` 完成。

## 注册 handler

```java
TaskWorker worker = orders.worker()
        .handle("order.cancel", context -> {
            cancelOrder(context.getPayload());
            return TaskResult.success();
        })
        .build()
        .start();
```

`handle(type, handler)` 做两件事：

1. 告诉 Worker 遇到 `order.cancel` 时执行这个函数；
2. 告诉后端这个 Worker 只抢占 `order.cancel`，不会抢占同队列里的其他任务类型。

一个 Worker 可以注册多个类型：

```java
TaskWorker worker = orders.worker()
        .handle("order.cancel", context -> {
            cancelOrder(context.getPayload());
            return TaskResult.success();
        })
        .handle("order.notify", context -> {
            sendNotification(context.getPayload());
            return TaskResult.success();
        })
        .build()
        .start();
```

同一个类型不能注册两个 handler。没有注册任何 handler 时，`build()` 会失败。

## 四种返回值

handler 最重要的事情是把结果说清楚：

```java
return TaskResult.success();

return TaskResult.failure("inventory is insufficient");

return TaskResult.cancel();

return TaskResult.retryAfter(Duration.ofSeconds(30));
```

| 返回值 | 任务状态 | 使用场景 |
| :--- | :--- | :--- |
| `success()` | `SUCCEEDED` | 业务目标已完成 |
| `failure(...)` | `FAILED` | 确认不能完成，需要人工或运维处理 |
| `cancel()` | `CANCELLED` |任务已经不需要执行 |
| `retryAfter(delay)` | `PENDING`，到达新的 `visibleAt` 后再执行 | 现在做不了，但稍后可能可以 |

一个常见的延迟重试 handler：

```java
.handle("payment.result-sync", context -> {
    PaymentState state = queryPayment(context.getPayload());
    if (!state.isPublished()) {
        return TaskResult.retryAfter(Duration.ofSeconds(30))
                .withErrorMessage("payment result is not published");
    }
    return TaskResult.success(
            state.toJson(),
            Collections.singletonMap("checkedAt", state.getCheckedAt()));
})
```

如果 handler 抛出普通异常，Worker 会把任务写成 `FAILED`，异常信息会进入 `errorMessage`。不要靠抛异常表达“稍后再试”；这种情况应返回 `retryAfter`。

## 返回新 payload 和属性

成功、失败和重试都可以写回新的 payload：

```java
return TaskResult.success(
        "{\"orderId\":\"O-1001\",\"cancelled\":true}",
        Collections.singletonMap("source", "worker"));
```

```java
return TaskResult.retryAfter(
        Duration.ofSeconds(30),
        "temporary upstream failure",
        "{\"paymentId\":\"P-1001\",\"checked\":true}",
        Collections.singletonMap("attempt", String.valueOf(context.getAttemptCount())));
```

省略 payload 表示保留原 payload；省略 attributes 表示保留原属性；传入空 map 表示清空属性。

## 常用配置

通常先使用默认值，只在你观察到问题后再调整：

| 方法 | 默认值 | 什么时候调整 |
| :--- | :--- | :--- |
| `workerId(String)` | `worker-` 加随机 UUID | 多实例部署或需要按实例排查时，显式指定 |
| `lease(Duration)` | 30 秒 | handler 正常耗时接近或超过 30 秒时调大 |
| `pollInterval(Duration)` | 250 毫秒 | 空闲很多且想减少轮询时调大；测试里可以调小 |
| `heartbeatEnabled(boolean)` | `true` | 长任务保持默认开启即可 |
| `heartbeatInterval(Duration)` | `lease / 3` | 一般不用改；必须大于 0 且小于 lease |

```java
TaskWorker worker = orders.worker()
        .handle("report.generate", context -> generateReport(context))
        .workerId("report-worker-1")
        .lease(Duration.ofMinutes(2))
        .heartbeatInterval(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .build()
        .start();
```

`lease` 是执行权有效期。如果任务没执行完但执行权已过期，其他 Worker 可以接管，旧 Worker 的最终写回会被拒绝。因此 lease 应大于最慢 handler 的正常耗时，并预留网络重试时间。超长工作建议拆成多个可重试的小任务。

## 停止 Worker

```java
worker.shutdown();
```

更明确地控制超时：

```java
if (!worker.shutdownGracefully(Duration.ofSeconds(5))) {
    worker.shutdownNow();
}
```

`shutdownGracefully` 停止接新任务并等待当前任务和写回完成；`shutdownNow` 中断工作线程并停止心跳；`shutdown` 和 `close` 会先按 lease 时长尝试优雅停机，超时后强制停机。`TaskWorker` 实现了 `AutoCloseable`，也可以用 try-with-resources。

每个 Worker 有一个工作线程，所以同一时刻最多处理一个任务。需要并发时，创建多个 Worker 或部署多个进程实例。

## 遇到基础设施故障时

普通异常表示业务失败，会被写成 `FAILED`。但有一种异常不应该终结任务：handler 周边的序列化器、配置加载或存储适配器坏了，业务本身还没真正开始或还没失败。

这种情况可以抛出：

```java
throw new TaskInfrastructureException("payload codec is unavailable", ex);
```

`TaskWorker` 会放弃本次执行权写回，让任务等待执行权过期后由其他 Worker 接管。不要把上游业务失败包装成这个异常，否则任务不会立即 `FAILED` 或 `retryAfter`，只能等执行权过期。
