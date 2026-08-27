# 租约任务组件 (team4u-lease)

`team4u-lease` 用一个队列化的任务模型提供排他执行、延迟调度、失败接管和运维查询。普通业务代码只需要理解四个概念：`TaskQueue`、`Task`、`TaskWorker` 和 `TaskResult`。

下面是可在单进程内直接运行的 Memory 示例：

```java
import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.Task;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.runtime.TaskWorker;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class MemoryQueueSample {
    public static void main(String[] args) throws Exception {
        TaskQueue orders = Leases.queue(new InMemoryLeaseBackend(), "orders");
        orders.submit(Task.of("order.timeout-cancel", "{\"orderId\":\"O-1001\"}")
                .deduplicationKey("O-1001")
                .delay(Duration.ofSeconds(15)));

        final CountDownLatch completed = new CountDownLatch(1);
        TaskWorker worker = orders.worker()
                .handle("order.timeout-cancel", context -> {
                    System.out.println("cancel order: " + context.getPayload());
                    TaskResult result = TaskResult.success(
                            "{\"orderId\":\"O-1001\",\"cancelled\":true}",
                            Collections.singletonMap("source", "worker"));
                    completed.countDown();
                    return result;
                })
                .lease(Duration.ofSeconds(30))
                .build()
                .start();

        try {
            if (!completed.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("task was not completed in time");
            }
        } finally {
            worker.shutdown();
        }
    }
}
```

> 上例为了快速结束使用 15 秒延迟；生产中通常会配置为业务需要的时间，例如 15 分钟。实际业务应把 `completed.countDown()` 放在 handler 返回前，或在服务生命周期中维护 Worker。

## 核心分层

| 层 | 面向 | 内容 |
| :--- | :--- | :--- |
| 业务 API | 任务发布方、Handler 开发者、运维调用方 | `TaskQueue`、`Task`、`TaskResult`、`TaskWorker`、查询与管理操作 |
| 存储 SPI | 后端实现者、基础设施团队 | 精确类型抢占、租约心跳、fencing、原子状态写回和契约测试 |

普通业务方不需要直接调用 `acquire`、`heartbeat`、`close` 或 `release`。这些协议由 `TaskWorker` 和 `TaskQueue` 统一执行。

## 交付语义

`team4u-lease` 提供的是排他调度和 **at-least-once** 执行：租约过期后任务会被其他 Worker 接管，因此网络中断、进程崩溃或写回失败都可能造成同一业务动作被再次执行。外部系统必须具备业务幂等能力，例如以业务单号作为去重键。

框架的幂等建档只保证同一 `(queue, taskType, deduplicationKey)` 只创建一条任务记录；它不等于业务动作只执行一次。

## 模块

| 模块 | 说明 | 适用场景 |
| :--- | :--- | :--- |
| `team4u-lease-core` | 业务 API、Worker、租约协议 SPI | 所有使用方 |
| `team4u-lease-memory` | 进程内后端 | 本地开发、单元测试、单进程排他 |
| `team4u-lease-jdbc` | JDBC/MySQL 后端、schema 和方言 | 跨进程持久化调度 |
| `team4u-lease-test` | 后端契约测试基类 | 自定义存储实现验证 |

## 文档导航

- [快速开始](quick-start.md)：Memory/JDBC 初始化、发布任务、启动 Worker
- [任务模型与状态机](lease-model.md)：五态模型、时间、幂等键、排序和 fencing
- [Worker 执行模型](lease-worker.md)：精确订阅、默认值、TaskResult、心跳和停机
- [存储后端实现](lease-backend.md)：Memory 结构、MySQL schema、typed SQL/CAS 和自定义后端
- [查询与管理](lease-admin.md)：查询、补数、重调度、失败重试和终态完成
- [实战示例](lease-sample.md)：延迟任务、后台补偿和运维修复
