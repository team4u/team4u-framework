# 快速开始

## 1. 引入依赖

本地开发或单元测试使用 Memory 后端：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-memory</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

跨进程生产部署使用 JDBC 后端：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-jdbc</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

两个模块都会传递引入 `team4u-lease-core`。

## 2. 初始化后端

### Memory

```java
InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
```

Memory 后端将任务保存在当前 JVM 中，进程退出后数据丢失，适合本地开发、测试和单进程排他调度。测试也可以注入 `java.time.Clock` 控制时间。

### JDBC

先在 MySQL 中执行 JDBC 模块 classpath 内的 schema：

```text
schema/lease_task_mysql.sql
```

通常有两种做法：

- 使用 Flyway/Liquibase 把该文件作为迁移脚本执行；
- 或由 DBA 用 MySQL 客户端执行一次。

JDBC 后端不负责建表。初始化表结构后，把你现有的连接池 `DataSource` 传给后端：

```java
JdbcLeaseBackend backend = new JdbcLeaseBackend(dataSource);
```

如果需要显式指定方言，也可以使用：

```java
JdbcLeaseBackend backend = new JdbcLeaseBackend(dataSource, new MySqlLeaseDbDialect());
```

当前默认方言就是 `MySqlLeaseDbDialect`。文档中的 MySQL schema 使用 `utf8mb4_bin` 二进制排序，队列名、任务类型和幂等键都区分大小写。

## 3. 获取队列

```java
TaskQueue orders = Leases.queue(backend, "orders");
```

队列名隔离任务、查询和管理操作。后端可以同时服务多个队列；`Leases.queue(...)` 只是基于同一个后端创建一个 queue-scoped 门面。

## 4. 发布任务

```java
Submission submission = orders.submit(Task
        .of("order.timeout-cancel", "{\"orderId\":\"O-1001\"}")
        .deduplicationKey("O-1001")
        .delay(Duration.ofMinutes(15))
        .priority(10)
        .attribute("traceId", "T-1001"));

System.out.println(submission.getTaskId());
System.out.println(submission.isCreated());
System.out.println(submission.getTask().getStatus());
```

`payload` 和 `attributes` 的值都是 `String`。上例直接使用 JSON 字面量；应用可以用自己的 JSON 工具先序列化再传入，组件不感知具体 JSON 库。

设置 `deduplicationKey` 后，同一 `(queue, taskType, deduplicationKey)` 只会创建一条任务；重复提交返回已有任务，并且 `Submission.isCreated()` 为 `false`。

## 5. 启动 Worker

```java
TaskWorker worker = orders.worker()
        .handle("order.timeout-cancel", context -> {
            System.out.printf("cancel %s, attempt=%d%n",
                    context.getPayload(), context.getAttemptCount());

            boolean cancelled = cancelOrder(context.getPayload());

            if (!cancelled) {
                return TaskResult.retryAfter(Duration.ofSeconds(30))
                        .withErrorMessage("order is still waiting for payment result");
            }

            return TaskResult.success(
                    "{\"orderId\":\"O-1001\",\"cancelled\":true}",
                    Collections.singletonMap("traceId", "T-1002"));
        })
        .workerId("order-worker-1")
        .lease(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(250))
        .build()
        .start();
```

Worker 只会抢占它注册了 handler 的任务类型，不会抢到同队列中未知类型后才发现无法处理。

服务关闭时释放 Worker：

```java
worker.shutdown();
```

也可以指定优雅停机超时；超时后执行强制停机：

```java
if (!worker.shutdownGracefully(Duration.ofSeconds(5))) {
    worker.shutdownNow();
}
```

## 完整 Memory 示例

```java
package demo;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.Task;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.runtime.TaskWorker;

import java.time.Duration;
import java.util.Collections;

public final class OrderCancelJob {
    public static void main(String[] args) throws Exception {
        TaskQueue orders = Leases.queue(new InMemoryLeaseBackend(), "orders");

        orders.submit(Task.of("order.timeout-cancel", "{\"orderId\":\"O-1001\"}")
                .deduplicationKey("O-1001"));

        TaskWorker worker = orders.worker()
                .handle("order.timeout-cancel", context -> {
                    System.out.println("cancel " + context.getPayload());
                    return TaskResult.success(
                            "{\"orderId\":\"O-1001\",\"cancelled\":true}",
                            Collections.singletonMap("source", "demo"));
                })
                .lease(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .build()
                .start();

        try {
            TaskStatus status = null;
            for (int i = 0; i < 250 && status != TaskStatus.SUCCEEDED; i++) {
                status = orders.list(TaskQuery.builder()
                                .type("order.timeout-cancel")
                                .build())
                        .getTasks()
                        .stream()
                        .findFirst()
                        .map(task -> task.getStatus())
                        .orElse(null);
                Thread.sleep(20L);
            }

            if (status != TaskStatus.SUCCEEDED) {
                throw new IllegalStateException("task did not succeed");
            }
        } finally {
            worker.shutdownGracefully(Duration.ofSeconds(2));
        }
    }
}
```


## JDBC 最小接线

```java
package demo;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.jdbc.JdbcLeaseBackend;

import javax.sql.DataSource;

public final class OrderQueueFactory {
    public static TaskQueue create(DataSource dataSource) {
        return Leases.queue(new JdbcLeaseBackend(dataSource), "orders");
    }
}
```

`DataSource` 来自你的连接池或运行时环境，例如 HikariCP、Druid、Spring DataSource 或应用服务器数据源；`team4u-lease-jdbc` 没有提供也不要求特定的 DataSource helper。

## 下一步

- [任务模型与状态机](lease-model.md)
- [Worker 执行模型](lease-worker.md)
- [查询与管理](lease-admin.md)
- [存储后端实现](lease-backend.md)
- [实战示例](lease-sample.md)
