# 快速开始

这条路径只需要 5 分钟：引入依赖，用 Memory 后端跑通第一个任务。生产使用的 JDBC 放在最后一节，第一次学习时可以先跳过。

## 1. 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-memory</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`team4u-lease-memory` 会传递引入 `team4u-lease`。Memory 后端把任务存在当前 JVM 里，进程退出后任务丢失，适合学习、开发和测试。

## 2. 创建队列

```java
InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
TaskQueue orders = Leases.queue(backend, "orders");
```

`orders` 是队列名。队列名会隔离任务、查询和管理操作；同一个后端可以创建多个不同队列。

## 3. 提交任务

```java
Submission submission = orders.submit(
        Task.of("order.cancel", "{\"orderId\":\"O-1001\"}"));

System.out.println(submission.getTaskId());
System.out.println(submission.getTask().getStatus());
```

`Task.of(type, payload)` 是最小提交方式：

- `type` 是任务类型，Worker 按它找到对应 handler；
- `payload` 是字符串，通常放 JSON；组件不解析内容，你自己决定编码方式。

## 4. 注册 handler 并启动 Worker

```java
TaskWorker worker = orders.worker()
        .handle("order.cancel", context -> {
            System.out.println("cancel order: " + context.getPayload());
            return TaskResult.success();
        })
        .build()
        .start();
```

`handle(type, handler)` 表示这个 Worker 能处理 `order.cancel`。Worker 只会抢占自己注册过 handler 的任务类型。handler 返回 `TaskResult.success()` 后，任务状态变为 `SUCCEEDED`。

如果暂时处理不了，可以返回稍后再试：

```java
return TaskResult.retryAfter(Duration.ofSeconds(30));
```

## 5. 观察任务状态

```java
TaskSnapshot task = orders.get(submission.getTaskId())
        .orElseThrow(() -> new IllegalStateException("task not found"));

System.out.println(task.getStatus());
System.out.println(task.getAttemptCount());
```

刚提交的任务是 `PENDING`，Worker 取出后变为 `RUNNING`，成功后变为 `SUCCEEDED`。演示或测试中可以循环查询，直到得到最终状态：

```java
TaskSnapshot done = null;
for (int i = 0; i < 250; i++) {
    TaskSnapshot current = orders.get(submission.getTaskId()).orElse(null);
    if (current != null && current.getStatus() == TaskStatus.SUCCEEDED) {
        done = current;
        break;
    }
    Thread.sleep(20L);
}
```

## 6. 关闭 Worker

服务退出时调用：

```java
worker.shutdown();
```

`shutdown()` 会先等待当前任务尽量完成，超时后强制停机。更明确的写法是：

```java
if (!worker.shutdownGracefully(Duration.ofSeconds(5))) {
    worker.shutdownNow();
}
```

## 可直接运行的完整例子

把下面文件放进你的项目后直接运行 `main`：

```java
package demo;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.Submission;
import com.team4u.framework.lease.api.Task;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.runtime.TaskWorker;

import java.util.concurrent.TimeUnit;

public final class MemoryQuickStart {
    public static void main(String[] args) throws Exception {
        TaskQueue orders = Leases.queue(new InMemoryLeaseBackend(), "orders");
        Submission submission = orders.submit(
                Task.of("order.cancel", "{\"orderId\":\"O-1001\"}"));

        TaskWorker worker = orders.worker()
                .handle("order.cancel", context -> {
                    System.out.println("cancel order: " + context.getPayload());
                    return TaskResult.success();
                })
                .build()
                .start();

        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            TaskSnapshot done = null;
            while (System.nanoTime() < deadline) {
                TaskSnapshot current = orders.get(submission.getTaskId()).orElse(null);
                if (current != null && current.getStatus() == TaskStatus.SUCCEEDED) {
                    done = current;
                    break;
                }
                Thread.sleep(20L);
            }
            if (done == null || done.getStatus() != TaskStatus.SUCCEEDED) {
                throw new IllegalStateException("task did not finish in time");
            }
            System.out.println("final status: " + done.getStatus());
        } finally {
            worker.shutdown();
        }
    }
}
```

预期输出：

```text
cancel order: {"orderId":"O-1001"}
final status: SUCCEEDED
```

## 常用任务选项

```java
Submission submission = orders.submit(Task
        .of("order.timeout-cancel", "{\"orderId\":\"O-1001\"}")
        .deduplicationKey("O-1001")
        .delay(Duration.ofMinutes(15))
        .priority(10)
        .attribute("traceId", "T-1001"));
```

- `deduplicationKey`：同一 `queue + taskType + deduplicationKey` 只创建一条任务；
- `delay`：到达时间前任务不会被执行；
- `priority`：数字越大越先被执行；
- `attribute`：附加业务信息，handler 里通过 `context.getAttributes()` 读取。

重复提交相同幂等键时，`submission.isCreated()` 返回 `false`，`submission.getTask()` 返回已有任务。

Memory 后端不序列化属性。下面的 JDBC 属性会写入 `attributes_json`，应用必须显式提供 JSON 引擎：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-serializer-jackson</artifactId>
</dependency>
```

也可以不用 Jackson provider，改为通过 `META-INF/services/com.team4u.framework.serializer.json.JsonSerializerPolicy` 注册自定义实现。`team4u-lease-jdbc` 不会传递选择任何 JSON 引擎。

## 可直接测试的租约生命周期

如果只想验证后端协议，也可以不启动 Worker，直接按提交、抢占、心跳、完成的顺序调用后端接口。执行权由 `taskId + workerId + leaseToken` 组成；伪造 token 的心跳会被拒绝，过期执行权不能写回结果：

```java
SubmitResult submission = backend.submit(SubmitCommand.of(
        "orders", "email.send", "{\"orderId\":\"O-1001\"}",
        "O-1001", 0L, 10, Collections.singletonMap("traceId", "T-1001")));

LeaseGrant grant = backend.acquire(AcquireCommand.of(
        TaskSubscription.of("orders", Collections.singleton("email.send")),
        "worker-a", 500L));

Assert.assertEquals(RuntimeResult.APPLIED,
        backend.heartbeat(grant.getHandle(), 700L));
Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
        LeaseCompletion.succeeded("{\"sent\":true}",
                Collections.singletonMap("traceId", "T-1001"))));
```

Memory 后端可以注入 `Clock` 控制时间，JDBC 后端测试构造器可以注入毫秒时钟，测试不需要 sleep。

## 准备上生产：改用 JDBC

跨进程部署时不要用 Memory 后端。先引入 JDBC 模块：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-jdbc</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

在 MySQL 中执行一次建表脚本。脚本文件在 JDBC 模块 classpath 中：

```text
schema/lease_task_mysql.sql
```

然后使用你现有的 `DataSource` 创建后端：

```java
JdbcLeaseBackend backend = new JdbcLeaseBackend(dataSource);
TaskQueue orders = Leases.queue(backend, "orders");
```

`DataSource` 来自你的连接池或运行时环境，例如 HikariCP、Druid、Spring 或应用服务器。JDBC 后端不负责建表，也不内置连接池。生产细节和风险见[存储后端](lease-backend.md)。

## 下一步

- [任务模型](lease-model.md)：状态、延迟、幂等键和尝试次数；
- [Worker 处理](lease-worker.md)：四种 `TaskResult` 和常用配置；
- [查询与管理](lease-admin.md)：查询、取消和重试；
- [示例场景](lease-sample.md)。
