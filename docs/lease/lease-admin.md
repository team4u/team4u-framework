# 查询与管理

所有操作都从 `TaskQueue` 发起，并自动携带队列名。不同队列之间不能通过 taskId 互查或互操作。

## 查询任务

按任务 ID 查询：

```java
Optional<TaskSnapshot> task = orders.get("task-id");
```

按幂等键查询必须提供 type 和 key：

```java
Optional<TaskSnapshot> task = orders.get("order.timeout-cancel", "O-1001");
```

分页和过滤：

```java
TaskPage page = orders.list(TaskQuery.builder()
        .type("order.timeout-cancel")
        .status(TaskStatus.PENDING)
        .workerId("order-worker-1")
        .page(0)
        .pageSize(50)
        .build());

long total = page.getTotal();
for (TaskSnapshot task : page.getTasks()) {
    System.out.printf("%s %s attempt=%d%n",
            task.getTaskId(), task.getStatus(), task.getAttemptCount());
}
```

`page` 从 0 开始，`pageSize` 默认 50。列表按 `createdAt`、`taskId` 升序返回；这里只做运维检索，不表示抢占顺序，抢占顺序由优先级、创建时间和可见资格决定。

`TaskQuery` 的三个过滤条件都可以为 `null`，但 workerId 过滤通常只在查询 `RUNNING` 任务时有值。

## 管理操作结果

所有管理方法返回同一个结果枚举：

| `TaskOperationResult` | 含义 |
| :--- | :--- |
| `APPLIED` | 条件满足，状态变更成功 |
| `TASK_NOT_FOUND` | 当前队列中不存在该任务 |
| `TERMINAL` | 任务已在 `SUCCEEDED`、`FAILED` 或 `CANCELLED`，拒绝变更 |
| `ACTIVE_LEASE_PRESENT` | 任务正在被有效 `RUNNING` 租约持有，拒绝管理面写入 |

这些方法不会抛出上述业务性冲突；冲突以返回值表达。参数非法、时间非法或后端故障仍会抛出运行时异常。

## 立即重调度

`reschedule` 把 PENDING 或已过期的 RUNNING 任务设置新的可见时间，并清空租约字段：

```java
TaskOperationResult result = orders.reschedule(
        "task-id", Duration.ofMinutes(5));
```

应用后：

- 状态为 `PENDING`;
- `visibleAt = now + delay`;
- `workerId`、`leaseToken`、`leaseExpiresAt` 清空；
- `errorMessage` 清空；
- `attemptCount` 不变，下一次抢占后再加 1。

活跃租约和终态会被拒绝。

## 修改任务数据

`TaskPatch` 是部分更新。未设置的字段保持不变：

```java
TaskOperationResult result = orders.update(TaskPatch.builder()
        .taskId("task-id")
        .payload("{\"orderId\":\"O-1001\",\"urgent\":true}")
        .priority(20)
        .attributes(Collections.singletonMap("traceId", "T-2"))
        .build());
```

属性语义需要特别注意：

- 未调用 `attributes(...)`：保留原属性；
- 调用 `attributes(emptyMap())`：清空全部属性；
- 调用 `attributes(map)`：用这个 map 替换全部属性。

`update` 只改元数据，不改变状态和时间。对租约已过期的 `RUNNING` 任务，它会保留 `RUNNING`、原 `workerId` 和原 `leaseExpiresAt`；之后其他 Worker 仍可按过期租约接管。

## 原子修改并重调度

`updateAndReschedule` 把元数据变更和重调度作为一个后端原子操作：

```java
TaskOperationResult result = orders.updateAndReschedule(
        TaskPatch.builder()
                .taskId("task-id")
                .type("order.timeout-cancel-v2")
                .payload("{\"orderId\":\"O-1001\",\"urgent\":true}")
                .priority(20)
                .attributes(Collections.singletonMap("traceId", "T-2"))
                .build(),
        Duration.ofMinutes(5));
```

应用后任务变为 `PENDING`，可见时间为 `now + delay`，租约字段和错误信息清空。活跃租约与终态同样被拒绝。

如果 patch 修改 type，新的三元组不能占用其他任务的 `(queue, taskType, deduplicationKey)` 幂等键。

## 重试失败任务

只有 `FAILED` 任务可以通过管理面 `retry` 重新调度：

```java
TaskOperationResult result = orders.retry("task-id", Duration.ofMinutes(1));
```

应用后任务为 `PENDING`，错误清空，`attemptCount` 保留并在下一次抢占时继续累计。`PENDING`、有效 `RUNNING`、`SUCCEEDED`、`CANCELLED` 都会被拒绝；其中有效 `RUNNING` 返回 `ACTIVE_LEASE_PRESENT`。

Handler 内部想立即安排下一次执行时应返回 `TaskResult.retryAfter(...)`，不是抛异常后调用管理面 retry。

## 完成和取消

无租约完成任务使用 `complete`，适用于 PENDING 或租约已过期的 RUNNING 任务：

```java
TaskOperationResult result = orders.complete(
        "task-id",
        TaskResult.success(
                "{\"reconciled\":true}",
                Collections.singletonMap("traceId", "T-2")));
```

`complete` 只接受 success、failure 或 cancel，不接受 retry 结果。它按 `TaskResult` 写入终态：

```java
orders.complete("task-id", TaskResult.failure("manual close", null, null));

orders.complete("task-id", TaskResult.cancel(
        "cancelled by operator", null, Collections.emptyMap()));
```

取消有快捷方法，等价于提交带 reason 的 cancel 结果：

```java
TaskOperationResult result = orders.cancel("task-id", "cancelled by operator");
```

活跃租约会返回 `ACTIVE_LEASE_PRESENT`，避免管理面覆盖正在执行的 Worker。终态任务返回 `TERMINAL`。

## 管理语义与后台接管

管理面可以在租约过期后修改或完成任务，即使快照仍显示旧的 `RUNNING` 记录。这是因为“租约是否有效”由当前时间和 `leaseExpiresAt` 判断，而不是依赖后台清理任务。

并发窗口下，后端必须原子判断管理条件和租约条件。若管理写回和 Worker 接管竞争，只有一方能成功；调用方应读取最新快照并按业务需要重试或放弃。
