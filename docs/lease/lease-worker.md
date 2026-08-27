# Worker 执行模型

`TaskWorker` 负责抢占、心跳、调用 handler 和写回结果。业务 handler 只处理 `TaskContext` 并返回 `TaskResult`，不直接操作租约。

```java
TaskWorker worker = orders.worker()
        .handle("order.timeout-cancel", context -> {
            boolean cancelled = cancelOrder(context.getPayload());
            if (!cancelled) {
                return TaskResult.retryAfter(Duration.ofSeconds(30))
                        .withErrorMessage("payment result is unavailable");
            }
            return TaskResult.success();
        })
        .handle("order.notify", context -> {
            try {
                sendNotification(context.getPayload());
                return TaskResult.success();
            } catch (TemporaryNotificationException ex) {
                throw ex;
            }
        })
        .build()
        .start();
```

## 精确类型订阅

`handle(type, handler)` 同时完成两件事：

1. 注册本地 handler；
2. 把该 type 加入后端抢占订阅。

Worker 的订阅是 `(queue, taskTypes)`，并且 task type 必须精确匹配，不支持通配符。因此：

- 未注册 handler 的类型不会被抢占；
- 旧版本 Worker 不会抢到新版任务后因缺 handler 而失败；
- 同一 Builder 中重复注册同一 type 会抛出 `IllegalArgumentException`;
- 至少注册一个 handler 后才能 build。

同一个 `TaskQueue` 可以创建多个 Worker，不同 Worker 可以处理不同 type 子集。

## TaskResult 映射

| Handler 返回 | Worker 写回 | 任务结果 |
| :--- | :--- | :--- |
| `TaskResult.success()` | terminal completion | `SUCCEEDED` |
| `TaskResult.failure(...)` | terminal completion | `FAILED` |
| `TaskResult.cancel(...)` | terminal completion | `CANCELLED` |
| `TaskResult.retryAfter(delay, ...)` | retry release | `PENDING`，`visibleAt = now + delay` |
| 抛出普通异常 | failure completion | `FAILED`，`errorMessage` 为异常信息 |
| 返回 `null` | failure completion | `FAILED`，错误为 `TaskHandler returned null` |

可选 patch 语义：

- payload 为 `null` 表示保留原 payload；
- 未调用 `withAttributes(...)` 或工厂未传 attributes 表示保留原属性；
- 传入空 attributes map 表示清空属性；
- failure/cancel 可以写 `errorMessage`，success 不允许写错误；
- retry 的 delay 必须是非负且可精确转换为毫秒的 `Duration`。

常见写法（这些 `return` 位于 `TaskHandler.handle` 方法内）：

```java
return TaskResult.success();

return TaskResult.success(
        "{\"processed\":true}",
        Collections.singletonMap("traceId", "T-2"));

return TaskResult.failure("inventory is insufficient");

return TaskResult.failure(
        "inventory is insufficient",
        "{\"remaining\":0}",
        Collections.emptyMap());

return TaskResult.cancel();

return TaskResult.retryAfter(Duration.ofMinutes(2));

return TaskResult.retryAfter(
        Duration.ofMinutes(2),
        "temporary upstream failure",
        "{\"nextCursor\":\"C-2\"}",
        Collections.singletonMap("attempt", "2"));
```

## 默认配置和可选项

| Builder 方法 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `workerId(String)` | `worker-` 加随机 UUID | 多实例部署建议显式指定，便于查询和排障 |
| `lease(Duration)` | 30 秒 | 抢占成功后的租约长度，必须为正 |
| `pollInterval(Duration)` | 250 毫秒 | 空闲或抢占失败后的休眠间隔；0 表示连续轮询 |
| `heartbeatEnabled(boolean)` | `true` | 是否自动续租 |
| `heartbeatInterval(Duration)` | `lease / 3` | 必须大于 0 且小于 lease |
| `threadName(String)` | `task-worker-{workerId}` | 工作线程和心跳线程名前缀 |

示例：

```java
TaskWorker worker = orders.worker()
        .handle("report.generate", context -> generateReport(context))
        .workerId("report-worker-1")
        .lease(Duration.ofMinutes(2))
        .heartbeatInterval(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .threadName("report-worker")
        .build();
```

lease 应大于最慢 handler 的正常执行时间，并预留网络重试窗口。长任务建议调大 lease，或把 handler 拆成可重试的小步骤。

## 心跳与接管

启用心跳时，Worker 每次处理任务都会启动独立心跳任务，按间隔调用后端续租。心跳结果不是 handler 的业务结果：

- 心跳失败只记录日志，handler 继续执行；
- 心跳返回 `LEASE_LOST` 会停止该任务的心跳，但不会主动中断 handler；
- 租约真正过期后，其他 Worker 可以接管；
- 旧 Worker 最终写回会被 fencing token 拒绝。

禁用心跳适合非常短、能在 lease 内稳定完成的任务。若任务超过 lease，即使业务已执行，终态写回也会因租约过期而失败。

## 停机

Worker 实现 `AutoCloseable`，可用 try-with-resources 关闭。

```java
try (TaskWorker worker = orders.worker()
        .handle("mail.send", context -> sendMail(context))
        .build()) {
    worker.start();
    service.awaitShutdown();
}
```

停机方法：

- `shutdownGracefully(Duration timeout)`：停止接新任务，等待当前任务和写回完成，返回是否在超时内停止；
- `shutdownNow()`：设置停机标记并中断工作线程，同时关闭心跳线程池；
- `shutdown()`：先按当前 lease 时长尝试优雅停机，超时后执行 `shutdownNow()`;
- `close()`：等价于 `shutdown()`。

优雅停机不会凭空保证 handler 完成：超过 lease 或租约被其他实例接管时，写回仍会被拒绝。业务必须保持幂等。

## 基础设施异常

普通异常会被 Worker 视为业务失败并写回 `FAILED`。如果 handler 周边的序列化器、外部配置或存储适配器故障不应该终结任务，可以抛出：

```java
throw new TaskInfrastructureException("payload codec is unavailable", ex);
```

`TaskWorker` 会放弃本次租约写回，让任务保持 `RUNNING`，等租约过期后由其他 Worker 接管。这是高级故障语义；普通业务失败仍应返回 `TaskResult.failure(...)` 或抛出普通异常。

不要把普通上游失败归类为 `TaskInfrastructureException`，否则任务会等待租约过期而不是立即进入 `FAILED` 或可配置延迟重试。

## 执行线程模型

每个 `TaskWorker` 有：

- 一个工作线程：循环抢占并同步执行 handler；
- 一个心跳单线程执行器：只为当前正在处理的任务续租；
- Worker 之间互不共享线程。

因此单个 Worker 同一时刻最多处理一个任务。需要并发时创建多个 `TaskWorker` 或多个进程实例，并让后端的抢占协议保证同一任务只被一个持有者处理。
