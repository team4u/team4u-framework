# 实战示例

以下示例都使用 String payload。JSON 字符串仅作为业务数据格式示例；组件本身不解析 JSON，也不要求引入任何 JSON 库。

## 延迟取消未支付订单

任务在创建后 1 秒才可见，便于示例快速完成；生产场景通常配置为 15 分钟。Worker 使用业务单号幂等取消。

```java
package demo;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.Submission;
import com.team4u.framework.lease.api.Task;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.runtime.TaskWorker;

import java.time.Duration;
import java.util.Collections;

public final class OrderTimeoutCancelDemo {
    public static void main(String[] args) throws Exception {
        TaskQueue orders = Leases.queue(new InMemoryLeaseBackend(), "orders");

        Submission submission = orders.submit(Task
                .of("order.timeout-cancel", "{\"orderId\":\"O-1001\"}")
                .deduplicationKey("O-1001")
                .delay(Duration.ofSeconds(1))
                .attribute("source", "checkout"));

        System.out.printf("created=%s taskId=%s%n",
                submission.isCreated(), submission.getTaskId());

        try (TaskWorker worker = orders.worker()
                .handle("order.timeout-cancel", context -> {
                    System.out.printf("attempt=%d cancel %s%n",
                            context.getAttemptCount(), context.getPayload());
                    cancelOrder(context.getPayload());
                    return TaskResult.success(
                            "{\"orderId\":\"O-1001\",\"cancelled\":true}",
                            Collections.singletonMap("traceId", "T-1002"));
                })
                .lease(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .build()) {

            worker.start();
            TaskSnapshot done = waitForStatus(orders, "order.timeout-cancel",
                    TaskStatus.SUCCEEDED, 5_000L);
            System.out.printf("final payload=%s%n", done.getPayload());
        }
    }

    private static void cancelOrder(String payload) {
        // 调用订单服务。这里必须以 orderId 做业务幂等。
        System.out.println("cancel order through order service: " + payload);
    }

    private static TaskSnapshot waitForStatus(
            TaskQueue orders, String type, TaskStatus status, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        TaskSnapshot last = null;
        while (System.nanoTime() < deadline) {
            for (TaskSnapshot snapshot : orders.list(TaskQuery.builder()
                    .type(type).build()).getTasks()) {
                last = snapshot;
                if (snapshot.getStatus() == status) {
                    return snapshot;
                }
            }
            Thread.sleep(20L);
        }
        throw new IllegalStateException("timeout, last status="
                + (last == null ? "none" : last.getStatus()));
    }
}
```

## 上游未就绪时的短周期补偿

第一次执行发现上游结果未发布，返回 `TaskResult.retryAfter`；第二次执行成功并写回新 payload。

```java
package demo;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.Task;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.runtime.TaskWorker;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PaymentResultCompensationDemo {
    public static void main(String[] args) throws Exception {
        TaskQueue payments = Leases.queue(new InMemoryLeaseBackend(), "payments");
        AtomicBoolean upstreamResultPublished = new AtomicBoolean(false);

        payments.submit(Task.of(
                "payment.result-sync",
                "{\"paymentId\":\"P-1001\"}").deduplicationKey("P-1001"));

        try (TaskWorker worker = payments.worker()
                .handle("payment.result-sync", context -> {
                    System.out.printf("attempt=%d payload=%s%n",
                            context.getAttemptCount(), context.getPayload());
                    if (!upstreamResultPublished.get()) {
                        upstreamResultPublished.set(true);
                        return TaskResult.retryAfter(
                                        Duration.ofMillis(50),
                                        "upstream payment result is not published",
                                        "{\"paymentId\":\"P-1001\",\"checked\":true}",
                                        Collections.singletonMap("attempt", "1"));
                    }

                    return TaskResult.success(
                            "{\"paymentId\":\"P-1001\",\"state\":\"PAID\"}",
                            Collections.singletonMap("attempt", "2"));
                })
                .lease(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .build()) {

            worker.start();
            TaskSnapshot done = waitUntilTerminal(payments, "payment.result-sync", 5_000L);
            if (done.getStatus() != TaskStatus.SUCCEEDED) {
                throw new IllegalStateException("compensation failed: " + done.getErrorMessage());
            }
            System.out.printf("attemptCount=%d payload=%s%n",
                    done.getAttemptCount(), done.getPayload());
        }
    }

    private static TaskSnapshot waitUntilTerminal(
            TaskQueue queue, String type, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        TaskSnapshot last = null;
        while (System.nanoTime() < deadline) {
            for (TaskSnapshot snapshot : queue.list(TaskQuery.builder()
                    .type(type).build()).getTasks()) {
                last = snapshot;
                if (snapshot.getStatus().isTerminal()) {
                    return snapshot;
                }
            }
            Thread.sleep(20L);
        }
        throw new IllegalStateException("timeout, last status="
                + (last == null ? "none" : last.getStatus()));
    }
}
```

如果每次执行都会失败并最终耗尽业务上限，handler 应在达到上限时返回 `TaskResult.failure(...)`，让任务进入 `FAILED`，再由运维决定是否调用管理面 retry。

## 运维修复延迟任务

任务原定 1 小时后执行。上游修复完成后，运维把 payload、优先级和可见时间一起原子更新，让任务立即重新调度。

```java
package demo;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.Task;
import com.team4u.framework.lease.api.TaskOperationResult;
import com.team4u.framework.lease.api.TaskPatch;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.runtime.TaskWorker;

import java.time.Duration;
import java.util.Collections;

public final class AdminRepairDemo {
    public static void main(String[] args) throws Exception {
        TaskQueue invoices = Leases.queue(new InMemoryLeaseBackend(), "invoices");

        invoices.submit(Task
                .of("invoice.repair", "{\"invoiceId\":\"I-1001\",\"batch\":\"old\"}")
                .deduplicationKey("I-1001")
                .delay(Duration.ofHours(1)));

        TaskSnapshot before = invoices.get("invoice.repair", "I-1001")
                .orElseThrow(() -> new IllegalStateException("task was not created"));
        if (before.getStatus() != TaskStatus.PENDING) {
            throw new IllegalStateException("task should be pending");
        }

        TaskOperationResult repaired = invoices.updateAndReschedule(
                TaskPatch.builder()
                        .taskId(before.getTaskId())
                        .payload("{\"invoiceId\":\"I-1001\",\"batch\":\"repaired\"}")
                        .priority(10)
                        .attributes(Collections.singletonMap("operator", "ops-1"))
                        .build(),
                Duration.ZERO);

        if (repaired != TaskOperationResult.APPLIED) {
            throw new IllegalStateException("repair failed: " + repaired);
        }

        try (TaskWorker worker = invoices.worker()
                .handle("invoice.repair", context -> {
                    System.out.printf("repair %s with %s%n",
                            context.getPayload(), context.getAttributes().get("operator"));
                    return TaskResult.success(context.getPayload(), context.getAttributes());
                })
                .lease(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .build()) {

            worker.start();
            long deadline = System.nanoTime() + 5_000_000_000L;
            TaskSnapshot last = null;
            while (System.nanoTime() < deadline) {
                last = invoices.list(TaskQuery.builder().type("invoice.repair").build())
                        .getTasks().stream().findFirst().orElse(null);
                if (last != null && last.getStatus().isTerminal()) {
                    break;
                }
                Thread.sleep(20L);
            }
            if (last == null || last.getStatus() != TaskStatus.SUCCEEDED) {
                throw new IllegalStateException("repair task did not succeed");
            }
        }
    }
}
```

`reschedule`、`retry`、`complete` 和 `cancel` 的完整条件语义见[查询与管理](lease-admin.md)。
