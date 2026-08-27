# 示例场景

这页只讲两个容易落地的场景。完整 API 请从[快速开始](quick-start.md)进入。

## 场景一：延迟取消未支付订单

业务规则：订单创建后 15 分钟未支付则取消。为了让示例不用真的等 15 分钟，这里使用 1 秒。

思路分三步：

1. 用户下单时提交一个延迟任务；
2. 任务到期后 Worker 查询订单支付状态；
3. 未支付就调用订单服务取消；取消操作必须以 `orderId` 幂等，因为任务可能被重复执行。

```java
TaskQueue orders = Leases.queue(new InMemoryLeaseBackend(), "orders");

Submission submission = orders.submit(Task
        .of("order.timeout-cancel", "{\"orderId\":\"O-1001\"}")
        .deduplicationKey("O-1001")
        .delay(Duration.ofSeconds(1)));

final CountDownLatch completed = new CountDownLatch(1);

try (TaskWorker worker = orders.worker()
        .handle("order.timeout-cancel", context -> {
            cancelIfUnpaid(context.getPayload());
            completed.countDown();
            return TaskResult.success();
        })
        .build()) {

    worker.start();
    if (!completed.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("task did not finish in time");
    }

    TaskSnapshot done = orders.get(submission.getTaskId())
            .orElseThrow(() -> new IllegalStateException("task not found"));
    System.out.println(done.getStatus());
}
```

关键点：

- `deduplicationKey("O-1001")` 避免同一个订单重复建档；
- `delay(Duration.ofSeconds(1))` 让任务 1 秒后才可执行；
- `cancelIfUnpaid` 内部要向订单服务传业务幂等键；
- 真实生产把 1 秒改成 15 分钟，并把 Memory 后端换成 JDBC 后端。

如果订单可能提前支付并触发撤回，可以在到期前调用 `orders.cancel(taskId, "order paid")`。查询和取消见[查询与管理](lease-admin.md)。

## 场景二：上游结果未就绪时稍后再查

业务规则：支付完成后要同步渠道结果，但渠道接口刚写入时可能暂时查不到。立即失败会把任务打成终态，更合适的是稍后重试。

思路：

1. 第一次执行发现结果未发布，返回 `retryAfter`；
2. 到达新的可执行时间后，同一个任务再次被 Worker 取走；
3. 第二次查到结果，写回成功 payload。

```java
TaskQueue payments = Leases.queue(new InMemoryLeaseBackend(), "payments");
AtomicBoolean resultPublished = new AtomicBoolean(false);

payments.submit(Task.of(
        "payment.result-sync",
        "{\"paymentId\":\"P-1001\"}").deduplicationKey("P-1001"));
final CountDownLatch completed = new CountDownLatch(2);

try (TaskWorker worker = payments.worker()
        .handle("payment.result-sync", context -> {
            if (!resultPublished.get()) {
                resultPublished.set(true);
                completed.countDown();
                return TaskResult.retryAfter(Duration.ofMillis(100))
                        .withErrorMessage("payment result is not published");
            }

            completed.countDown();
            return TaskResult.success(
                    "{\"paymentId\":\"P-1001\",\"state\":\"PAID\"}",
                    Collections.singletonMap("source", "payment-channel"));
        })
        .build()) {

    worker.start();
    if (!completed.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("task did not finish in time");
    }
}
```

第一次执行后任务仍是 `PENDING`，不是 `FAILED`；示例里的 100 毫秒后会再次执行，生产可以按外部系统写入延迟改成 30 秒或更长。`attemptCount` 从 1 变成 2，可以在 handler 里用它控制最大尝试次数。

如果某个错误确定不可能自动恢复，应返回：

```java
return TaskResult.failure("payment channel rejected this payment");
```

任务进入 `FAILED` 后，修复问题再由运维调用 `payments.retry(taskId, delay)`。
