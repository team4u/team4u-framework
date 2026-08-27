# team4u-lease 新人指南

`team4u-lease` 做一件很简单的事：**把任务放进队列，后台 Worker 取出任务并执行，最后记录执行结果**。

它适合这三类工作：

- 延迟任务：例如订单 15 分钟未支付后自动取消；
- 后台补偿：例如支付结果暂时查不到，稍后再查；
- 单任务排他处理：同一个任务同一时刻只给一个 Worker 执行。

新人先只需要记住五个词：

| 词 | 直观理解 |
| :--- | :--- |
| queue | 任务队列，例如 `orders` |
| task | 一件要做的事，包含任务类型和字符串数据 |
| handler | 你写的处理函数 |
| worker | 后台线程，不断取出 task 并调用 handler |
| result | handler 的返回值，表示成功、失败、取消或稍后再试 |

下面这个例子可以在单进程内直接运行。任务没有延迟，提交后马上会被 Worker 执行：

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

public final class FirstTaskDemo {
    public static void main(String[] args) throws Exception {
        // 1. 创建一个名为 orders 的任务队列。
        //    InMemoryLeaseBackend 把任务存在当前 JVM 里，适合本地运行和学习。
        TaskQueue orders = Leases.queue(new InMemoryLeaseBackend(), "orders");

        // 2. 提交任务。
        //    "order.cancel" 是任务类型，用来匹配 Worker 里的 handler；
        //    payload 是传给 handler 的字符串数据，这里用一个简单的 JSON 表示订单号。
        Submission submission = orders.submit(
                Task.of("order.cancel", "{\"orderId\":\"O-1001\"}"));

        // 3. 创建并启动 Worker。Worker 会不断从 orders 队列取任务。
        TaskWorker worker = orders.worker()
                // 只处理 order.cancel 类型的任务；其他类型会留给能处理它们的 Worker。
                .handle("order.cancel", context -> {
                    // context.getPayload() 就是提交任务时传入的字符串。
                    System.out.println("处理任务: " + context.getPayload());
                    // handler 返回 success 后，框架会把任务状态写成 SUCCEEDED。
                    return TaskResult.success();
                })
                .build()
                .start();

        // 4. 主线程轮询任务状态，直到 Worker 写回最终结果。
        //    Worker 在另一个线程执行，所以这里不能提交后立刻查询一次就结束。
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            TaskStatus status = null;
            while (System.nanoTime() < deadline) {
                status = orders.get(submission.getTaskId())
                        .map(TaskSnapshot::getStatus)
                        .orElse(null);
                if (status == TaskStatus.SUCCEEDED) {
                    break;
                }
                Thread.sleep(20L);
            }
            if (status != TaskStatus.SUCCEEDED) {
                throw new IllegalStateException("unexpected final status: " + status);
            }
            System.out.println("任务最终状态: " + status);
        } finally {
            // 5. 服务退出前关闭 Worker，避免线程泄漏。
            worker.shutdown();
        }
    }
}
```

预期输出：

```text
处理任务: {"orderId":"O-1001"}
任务最终状态: SUCCEEDED
```

## 第一次运行

使用 [快速开始](quick-start.md)。那里只走一条路径：引入 Memory 后端、创建队列、提交任务、启动 Worker、观察状态、关闭 Worker。

## 为什么任务可能重复执行

Worker 执行前会先获得一份带到期时间的执行权。可以把它想成“工位牌”：拿着牌的人可以处理任务；牌过期后，为了避免任务永远卡住，其他 Worker 可以领新牌继续处理。

因此，如果进程崩溃、网络中断，或者业务已经做完但结果没写回，任务可能会被再次执行。这叫 **at-least-once**：框架保证任务会被处理，不保证业务动作只发生一次。

所以 handler 里的业务操作要能重复执行。例如取消订单时，以 `orderId` 作为业务幂等键；即使取消请求被发送两次，订单服务也只取消一次。

## 为什么有 deduplicationKey

`deduplicationKey` 解决的是“重复建档”，不是“重复执行”。

例如用户连续点了三次“取消订单”，你可以都提交同一个 key：

```java
Task.of("order.cancel", payload)
        // 相同 queue + taskType + deduplicationKey 只会创建一条任务记录。
        .deduplicationKey("O-1001");
```

同一个队列里，`queue + taskType + deduplicationKey` 完全相同时，后端只创建一条任务记录；重复提交会返回已有任务。不同任务类型可以使用同一个 key。

更完整的模型说明见[任务模型](lease-model.md)。

## 下一步阅读

1. [快速开始](quick-start.md)：跑通第一个任务；
2. [任务模型](lease-model.md)：理解状态、延迟、幂等键和尝试次数；
3. [Worker 处理](lease-worker.md)：写业务 handler 和返回 `TaskResult`；
4. [查询与管理](lease-admin.md)：查任务、取消任务、重试失败任务；
5. [存储后端](lease-backend.md)：准备生产 JDBC 或实现自定义存储；
6. [示例场景](lease-sample.md)：延迟取消和后台补偿。
