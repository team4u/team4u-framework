# 任务模型

这一页解释任务从提交到结束的样子。你不需要先理解数据库表或后端协议；只看 `TaskQueue` 看到的对象就够了。

## 核心对象

| 对象 | 你什么时候遇到 | 作用 |
| :--- | :--- | :--- |
| `Task` | 提交任务时 | 你要创建的任务：类型、字符串 payload、延迟、优先级、幂等键和属性 |
| `Submission` | 调用 `submit` 后 | 提交结果：任务 ID、是否新建、提交时的任务快照 |
| `TaskSnapshot` | 查询任务时 | 后端当前记录的任务状态和数据 |
| `TaskContext` | handler 执行时 | handler 能看到的任务内容，不包含执行权细节 |
| `TaskResult` | handler 返回时 | 告诉 Worker 任务成功、失败、取消，还是稍后再试 |

最小任务只需要两个字段：

```java
Task task = Task.of("order.cancel", "{\"orderId\":\"O-1001\"}");
```

也可以继续配置：

```java
Task task = Task.of("order.timeout-cancel", "{\"orderId\":\"O-1001\"}")
        .deduplicationKey("O-1001")
        .delay(Duration.ofMinutes(15))
        .priority(10)
        .attribute("traceId", "T-1001");
```

`payload` 和 `attributes` 的值都是字符串。你可以放 JSON、普通文本或自己的编码格式；组件不解析内容。

## 五个状态

| 英文状态 | 中文理解 | 什么时候出现 |
| :--- | :--- | :--- |
| `PENDING` | 排队中 | 任务已创建，还没有 Worker 取走；到达 `visibleAt` 之前即使排队中也不会被取走 |
| `RUNNING` | 执行中 | 某个 Worker 已取走任务，正在调用 handler |
| `SUCCEEDED` | 成功 | handler 返回 success，或管理面补记成功 |
| `FAILED` | 失败 | handler 返回 failure、抛普通异常，或管理面补记失败 |
| `CANCELLED` | 已取消 | handler 返回 cancel，或管理面取消 |

前三个常见流转是：

```text
PENDING -> RUNNING -> SUCCEEDED
PENDING -> RUNNING -> FAILED
PENDING -> RUNNING -> CANCELLED
```

如果 handler 说“现在做不了，稍后再试”：

```text
PENDING -> RUNNING -> PENDING -> RUNNING -> ...
```

对应代码是：

```java
return TaskResult.retryAfter(Duration.ofSeconds(30));
```

如果确认不能再继续：

```java
return TaskResult.failure("inventory is insufficient");
```

失败任务是终态。运维确认后可以调用 `orders.retry(taskId, delay)` 让它重新排队。不要把有限次业务重试理解成框架自动重试；什么时候继续、什么时候停止，由 handler 或调用方决定。

## 幂等键

`deduplicationKey` 的唯一范围是：

```text
(queue, taskType, deduplicationKey)
```

规则：

- 不设置 key：每次 `submit` 都创建新任务；
- 设置 key：同一个三元组重复提交时，返回已有任务，`Submission.isCreated()` 为 `false`；
- 不同 task type 可以使用同一个 key；
- 不同 queue 也可以使用同一个 key；
- 三个字段都区分大小写。

重复提交不会更新旧任务的 payload。它只避免重复建档。

## 时间字段

`TaskSnapshot` 里有三个时间：

| 字段 | 含义 |
| :--- | :--- |
| `createdAt` | 任务创建时间 |
| `visibleAt` | 最早可执行时间；`delay(15分钟)` 会把它设置成创建时间后 15 分钟 |
| `leaseExpiresAt` | 当前执行权的到期时间；只有 `RUNNING` 任务有值 |

`visibleAt` 回答“什么时候可以开始”；`leaseExpiresAt` 回答“这次执行权什么时候失效”。二者无关：

- `visibleAt` 未到：任务仍是 `PENDING`，Worker 不会取走；
- `leaseExpiresAt` 已到：其他 Worker 可以接管这个 `RUNNING` 任务；
- 接管和续租不会修改 `visibleAt`；
- handler 返回 retry 或运维重调度时，`visibleAt` 会变成“当前时间 + delay”。

业务 API 的时间参数使用 `Duration`，快照时间使用 `Instant`。负数、小于 1 毫秒的纳秒，或超过毫秒范围的时长会被拒绝。

## 执行次数

`attemptCount` 表示任务被 Worker 成功取走的次数：

```text
提交后：0
第一次执行：1
retryAfter 后再次执行：2
执行权过期并被接管：继续加 1
```

它不是 handler 抛异常次数，也不是业务失败次数。handler 可以通过 `context.getAttemptCount()` 读取它，用于设置自己的最大尝试次数。

## 交付语义

组件提供的是 **at-least-once** 调度：任务会被尽力处理，但业务动作可能发生多次。

可能出现重复执行的情况包括：

- Worker 进程崩溃或被强制杀死；
- handler 已经调用外部系统成功，但任务结果没写回；
- Worker 长时间暂停后恢复，继续执行旧逻辑；
- 执行权过期后，其他 Worker 接管。

因此外部业务必须幂等。例如取消订单时以 `orderId` 去重，发送消息时使用业务消息 ID。`deduplicationKey` 只解决重复提交任务，不能代替外部系统幂等。

## 深入理解：抢占顺序

多个任务都到达 `visibleAt` 后，Worker 按以下顺序选择：

1. `priority` 降序，数字越大越先；
2. `createdAt` 升序，越早创建越先；
3. `taskId` 升序，作为稳定排序。

`visibleAt` 只是资格条件，不参与排序。一个后创建但优先级更高的任务，到达可见时间后可以排在更早创建的低优先级任务前面。
