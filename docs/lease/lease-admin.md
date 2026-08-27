# 查询与管理

这一页面向需要看任务状态或做简单运维操作的人。所有操作都从 `TaskQueue` 发起，并且只作用于当前队列。

## 最常用：查一个任务

你拿到 `taskId` 后，先查快照确认状态：

```java
Optional<TaskSnapshot> task = orders.get("task-id");
```

如果提交时设置了幂等键，可以按类型和 key 查：

```java
Optional<TaskSnapshot> task = orders.get("order.cancel", "O-1001");
```

常见判断：

```java
TaskSnapshot snapshot = orders.get("task-id")
        .orElseThrow(() -> new IllegalStateException("task not found"));

System.out.println(snapshot.getStatus());
System.out.println(snapshot.getErrorMessage());
System.out.println(snapshot.getAttemptCount());
```

也可以按条件分页查询：

```java
TaskPage page = orders.list(TaskQuery.builder()
        .type("order.cancel")
        .status(TaskStatus.PENDING)
        .page(0)
        .pageSize(50)
        .build());

System.out.println(page.getTotal());
for (TaskSnapshot task : page.getTasks()) {
    System.out.printf("%s %s attempt=%d%n",
            task.getTaskId(), task.getStatus(), task.getAttemptCount());
}
```

`page` 从 0 开始，`pageSize` 默认 50。列表按创建时间排序，用于运维检索；它不代表 Worker 的抢占顺序。

## 最常用：取消一个任务

用户撤回请求、需求变更，或者你确认任务不需要再执行时，可以取消：

```java
TaskOperationResult result = orders.cancel(
        "task-id", "order was paid manually");
```

只有排队中或执行权已过期的任务能取消。正在执行、成功、失败、已取消的任务会返回冲突值。

## 最常用：重试失败任务

任务已经 `FAILED`，问题修复后想让队列再执行一次：

```java
TaskOperationResult result = orders.retry(
        "task-id", Duration.ofMinutes(1));
```

这里的 `retry` 是运维操作，只作用于 `FAILED` 任务。handler 内部想安排下一次执行时，应返回 `TaskResult.retryAfter(...)`，不要在 handler 里调用这个方法。

调用成功后：

- 状态回到 `PENDING`；
- `visibleAt` 变为当前时间加 1 分钟；
- `errorMessage` 清空；
- `attemptCount` 保留，下一次被 Worker 取走时继续加 1。

## 管理操作返回值

查询以外的主要管理方法都返回 `TaskOperationResult`：

| 值 | 含义 |
| :--- | :--- |
| `APPLIED` | 操作成功 |
| `TASK_NOT_FOUND` | 当前队列没有这个任务 |
| `TERMINAL` | 任务已是成功、失败或取消，不能再变更 |
| `ACTIVE_LEASE_PRESENT` | 任务正在被有效执行，不能从管理面覆盖 |

```java
TaskOperationResult result = orders.cancel("task-id", "not needed");
if (result != TaskOperationResult.APPLIED) {
    System.out.println("cancel failed: " + result);
}
```

## 补记终态

有些工作不是 Worker 执行的，例如人工核对后想把排队中的任务直接标记为成功或失败：

```java
TaskOperationResult result = orders.complete(
        "task-id",
        TaskResult.success(
                "{\"reconciled\":true}",
                Collections.singletonMap("operator", "ops-1")));
```

也可以补记失败或取消：

```java
orders.complete("task-id", TaskResult.failure("manual close"));

orders.cancel("task-id", "cancelled by operator");
```

`complete` 不接受 `TaskResult.retryAfter(...)`；需要重新调度时使用 `reschedule` 或 `retry`。

## 重新调度

任务还没失败，但你想改变它下次可执行的时间：

```java
TaskOperationResult result = orders.reschedule(
        "task-id", Duration.ofMinutes(5));
```

成功后任务回到 `PENDING`，`visibleAt` 变为当前时间加 5 分钟，执行权字段和错误信息清空。排队中任务和执行权已过期的任务可以重调度；正在执行和终态任务会被拒绝。

handler 返回 retry 时已经完成同样的事情，一般不需要再手动 reschedule。

## 高级：修改任务数据

`TaskPatch` 是部分更新，适合运维修复排队中的任务：

```java
TaskOperationResult result = orders.update(TaskPatch.builder()
        .taskId("task-id")
        .payload("{\"orderId\":\"O-1001\",\"urgent\":true}")
        .priority(20)
        .attributes(Collections.singletonMap("traceId", "T-2"))
        .build());
```

未设置的字段保持不变。`attributes` 需要注意：

- 未调用 `attributes(...)`：保留原属性；
- 调用 `attributes(Collections.emptyMap())`：清空全部属性；
- 调用 `attributes(map)`：用新 map 替换全部属性。

`update` 只改数据，不改变状态和执行时间。对执行权已过期的 `RUNNING` 记录，它会保留原状态和旧 Worker 信息；之后其他 Worker 仍可接管。

## 高级：原子修改并重调度

如果要把修复数据和立即重调度合并成一次后端更新，避免中间被 Worker 抢走：

```java
TaskOperationResult result = orders.updateAndReschedule(
        TaskPatch.builder()
                .taskId("task-id")
                .payload("{\"orderId\":\"O-1001\",\"batch\":\"repaired\"}")
                .priority(10)
                .build(),
        Duration.ZERO);
```

成功后任务变为 `PENDING`，可见时间为当前时间加 `Duration.ZERO`，执行权字段清空。如果 patch 修改 `type`，新的 `(queue, taskType, deduplicationKey)` 不能已经被其他任务占用。

## 并发注意事项

管理面看到 `RUNNING` 但执行权已经过期时，操作仍然可能成功。执行权是否有效由当前时间和 `leaseExpiresAt` 判断，不依赖后台清理任务把状态改回 `PENDING`。

管理操作和 Worker 接管可能同时发生。后端会原子判断条件，只有一方成功。调用方拿到冲突返回值时，应重新查询任务，再决定是否重试操作。
