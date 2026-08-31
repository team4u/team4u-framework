# 通用重试组件 (team4u-retry)

# 背景

`team4u-retry` 帮你把“失败的调用再试一次”写成明确、可控的代码。它解决两类常见问题：

- 调用下游短暂失败，想马上在当前请求里重试几次并拿到结果。
- 前几次失败后不想继续占着用户线程，希望把剩余重试存下来，交给后台慢慢补偿。

---

# 设计

## 设计理念

组件提供两种接入模式，但用同一套 `RetryPolicy` 描述“最多再试几次、每次等多久、哪些异常才重试”。这样业务代码可以在进程内同步或异步执行，也可以把未完成的重试持久化后交给后台 Worker。

`maxRetries` 不包含首次执行：`2` 表示最多总共执行 3 次。MANAGED 还必须配置 `foregroundMaxRetries`，它表示首次执行之后前台额外尝试几次；任务进入后台时已失败次数不会归零。

`team4u-retry` 的 JSON 配置由应用显式提供 JSON 引擎：添加 `team4u-serializer-jackson` 或注册自定义 `JsonSerializerPolicy`。`team4u-retry-managed` 的 `JsonRetryRecordSerializer` 使用同一 JSON SPI。`team4u-retry-lease-runtime` 是长期 Jackson 集成模块：`LeaseRetryRecordSerializer` 直接使用 Jackson tree API 实现版本化持久 schema、字段级校验和 throwable allowlist；通用 serializer SPI 无法表达这些约束。该直接 Jackson 生产依赖是发布契约中的显式例外，不传递 `team4u-serializer-jackson`。
---

## 模式选择

| 你要做的事 | 选这个 | 一句话效果 |
| :--- | :--- | :--- |
| 当前请求里很快重试，最终拿到返回值或异常 | `INLINE` | 阻塞当前调用，直到成功、失败或次数耗尽 |
| 前台快速尝试，失败后持久化交给后台补偿 | `MANAGED` | 前台少量尝试，然后返回“已接手”，后台继续重试 |

简单判断：**等得起、必须有返回值，用 INLINE；等不起或进程重启后也必须继续补偿，用 MANAGED。**

---

## 快速上手

### 最小 INLINE 示例

```java
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;

import java.io.IOException;

public class SmsClientDemo {

    public static void main(String[] args) throws Exception {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(2) // 不含首次执行；总共最多执行 3 次
                .backoff(Backoffs.fixed(200))
                .retryOn(IOException.class)
                .build();

        String result = Retries.inline()
                .policy(policy)
                .call(() -> sendSms("13800000000", "1234"));

        System.out.println(result);
    }

    private static final java.util.concurrent.atomic.AtomicInteger CALLS
            = new java.util.concurrent.atomic.AtomicInteger();

    private static String sendSms(String mobile, String code) throws IOException {
        if (CALLS.incrementAndGet() < 3) {
            throw new IOException("sms gateway timeout");
        }
        return "sms sent: " + mobile + ", code=" + code;
    }
}
```

`RetryPolicy` 就是“最多再试几次 + 每次等多久 + 哪些异常才重试”。若 3 次都失败，最后一次的业务异常会直接抛给调用方。

### 最小 MANAGED 示例

MANAGED 比 INLINE 多三件事：要指定后台恢复处理器；要传幂等键；要用 `ManagedRetryRuntime` 启动后台线程。完整可运行示例如下：

```java
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.retry.managed.ManagedSubmitResult;
import com.team4u.framework.retry.managed.ManagedRetries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import com.team4u.framework.retry.runtime.lease.ManagedRetryRuntime;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
public class PayNotifyDemo {

    public static void main(String[] args) throws Exception {
        CountDownLatch recovered = new CountDownLatch(1);
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        registry.register(new NotifyAgainHandler(recovered));

        ManagedRetryRuntime runtime = ManagedRetryRuntime
                .lease(new InMemoryLeaseBackend())
                .registry(registry)
                .autoScanRecoveryHandlers(false)
                .defaultPolicy(RetryPolicy.builder()
                        .maxRetries(3)
                        .foregroundMaxRetries(0)
                        .backoff(Backoffs.fixed(200))
                        .build())
                .start();

        try {
            ManagedSubmitResult<String> result = ManagedRetries.with(runtime.client())
                    .taskType("pay-notify")
                    .idempotencyKey("order-1001")
                    .payload("order-1001")
                    .policy(RetryPolicy.builder()
                            .maxRetries(3)
                            .foregroundMaxRetries(0)
                            .backoff(Backoffs.fixed(200))
                            .build())
                    .call(PayNotifyDemo::notifyMerchant);

            if (result.isCompleted()) {
                System.out.println("前台完成: " + ((ManagedSubmitResult.Completed<String>) result).getValue());
            } else if (result.isAccepted()) {
                System.out.println("已交给后台: "
                        + ((ManagedSubmitResult.Accepted<String>) result).getTaskId());
            } else if (result.isFailed()) {
                System.out.println("前台终态失败: "
                        + ((ManagedSubmitResult.Failed<String>) result).getError().getMessage());
            }
            if (result.isAccepted() && !recovered.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("background recovery timed out");
            }
        } finally {
            runtime.close();
        }
    }

    private static String notifyMerchant() throws Exception {
        // 首次失败后，任务进入后台，由 NotifyAgainHandler 补偿
        throw new IOException("merchant unavailable");
    }

    private static final class NotifyAgainHandler implements StringRecoveryHandler {
        private final CountDownLatch recovered;

        private NotifyAgainHandler(CountDownLatch recovered) {
            this.recovered = recovered;
        }

        @Override
        public String taskName() {
            return "pay-notify";
        }

        @Override
        public void recover(String payload, RecoveryContext context) {
            System.out.println("后台通知成功: " + payload + ", attempt=" + context.getAttempt());
            recovered.countDown();
        }
    }
}
```

运行时会先打印 `已交给后台: ...`，随后打印类似 `后台通知成功: order-1001, attempt=2`。

Memory 后端只适合演示和单进程测试。部署到多个进程时，把 `new InMemoryLeaseBackend()` 换成 JDBC 后端，后台任务才能被共享和接管。

---

## 核心概念

- `Retries.inline()`: 进程内重试入口。
- `ManagedRetries.with(runtime.client())`: 托管重试入口（来自 `team4u-retry-managed`）。
- `RetryPolicy`: 最多再试几次、每次等多久、哪些异常才重试。
- `Backoffs`: 固定、递增、指数、指数加随机抖动。
- `StringRecoveryHandler`: MANAGED 后台恢复处理器，入参固定为字符串 payload。
- `ManagedRetryRuntime`: 组装队列、存储和后台 Worker。
- `ManagedSubmitResult`: 前台完成、已交后台、命中已有任务、终态失败或基础设施拒绝。

---

## 与流程组件集成 (`team4u-flow-retry`)

当在流程编排组件 [`team4u-flow`](../flow/README.md) 中使用重试治理时，可引入专用适配模块 `team4u-flow-retry`：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-retry</artifactId>
</dependency>
```

- **统一退避体系**：将 `Backoffs`（固定、等差递增、指数、随机抖动等）无缝带入 Flow 流程节点治理中；
- **条件重试与快速失败**：通过 `Predicate<Failure>` 过滤可重试故障码，非重试异常触发 `Rejected` 快速退出；
- **动态规则对接**：天然支持从 `NamedRetryPolicyRegistry` / `DynamicRetryPolicyRegistry` 按名称热加载重试配置；
- **使用示例**：
  ```java
  FlowRetryPolicy<OrderRequest> policy = FlowRetryPolicy.exponential(3, 100, 2.0, 1000);
  Flow<OrderRequest, Receipt> flow = Flow.step(chargeOperation)
          .persistentPolicy(policy, OrderRequest::getUserId);
  ```
详细说明请参阅 [流程治理：Policy 策略、Retry 重试与 Timeout 控制](../flow/flow-governance.md)。

---

## 文档导航

- [快速开始](quick-start.md)：分别跑通 INLINE 和 MANAGED
- [进程内重试 INLINE](retry-inline.md)：同步、异步和异常判定
- [托管持久化重试 MANAGED](retry-managed.md)：后台恢复、故障边界和生产配置
- [退避策略](retry-strategy.md)：选择每次重试之间等多久
- [注解与代理](retry-proxy.md)：声明式接入
- [Spring 整合](retry-spring.md)：Spring 环境装配
- [实战案例](retry-sample.md)：业务场景落地
