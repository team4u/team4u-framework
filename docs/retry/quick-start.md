# 快速开始

先跑通一条路径，再看另一条。两条路径互不依赖：

- [INLINE：当前请求里重试](#路径一-inline当前请求里重试)
- [MANAGED：失败后交给后台补偿](#路径二-managed失败后交给后台补偿)

## 引入依赖

只用 INLINE，引入 `team4u-retry-core`：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

使用本文的 MANAGED 示例，再引入运行时和 Memory 后端：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-lease-runtime</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-memory</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Memory 后端用于本机示例和单进程测试。多进程生产环境使用 JDBC，见[部署到多进程](#部署到多进程使用-jdbc)。

## 路径一： INLINE，当前请求里重试

适合“等 200ms、800ms 也可以，但这次调用必须拿到结果”的场景。下面的任务前两次失败，第三次成功：

```java
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

AtomicInteger calls = new AtomicInteger();

RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(2) // 不含首次执行，所以总共最多执行 3 次
        .backoff(Backoffs.fixed(200))
        .retryOn(IOException.class)
        .build();

String result = Retries.inline()
        .policy(policy)
        .call(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IOException("temporary failure");
            }
            return "ok";
        });

System.out.println("result=" + result + ", calls=" + calls.get());
```

你应该看到：

```text
result=ok, calls=3
```

如果三次都失败，`Retries.inline().call(...)` 会抛出最后一次业务异常，不会包装成框架异常。没有配置 `retryOn` 时，所有非中断异常都会按次数策略重试。

## 路径二： MANAGED，失败后交给后台补偿

适合通知、补发、外部系统写入这类动作：前台先试一次，失败后不再拖住用户请求，任务被存起来，由后台继续执行。

这个例子有四个角色：

- `notifyMerchant`: 前台业务动作，这里固定失败，用来触发后台接管。
- `NotifyAgainHandler`: 后台恢复动作，成功后打印 payload。
- `ManagedRetryRuntime`: 创建后台 Worker 和存储。
- `ManagedRetries.with(...)`: 提交前台任务和恢复信息（来自 `team4u-retry-managed`）。

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

public class ManagedQuickStart {

    public static void main(String[] args) throws Exception {
        CountDownLatch recovered = new CountDownLatch(1);

        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        registry.register(new NotifyAgainHandler(recovered));

        ManagedRetryRuntime runtime = ManagedRetryRuntime
                .lease(new InMemoryLeaseBackend())
                .registry(registry)
                .autoScanRecoveryHandlers(false)
                .defaultPolicy(retryPolicy())
                .start();

        try {
            ManagedSubmitResult<String> result = ManagedRetries.with(runtime.client())
                    .taskType("pay-notify")
                    .idempotencyKey("order-1001")
                    .payload("order-1001")
                    .policy(retryPolicy())
                    .call(() -> {
                        throw new IOException("merchant unavailable");
                    });

            if (result.isAccepted()) {
                ManagedSubmitResult.Accepted<String> accepted =
                        (ManagedSubmitResult.Accepted<String>) result;
                System.out.println("accepted taskId=" + accepted.getTaskId());
            }

            if (!recovered.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("background recovery timed out");
            }
        } finally {
            runtime.close();
        }
    }

    private static RetryPolicy retryPolicy() {
        return RetryPolicy.builder()
                .maxRetries(2)       // 首次之后最多再试 2 次，总共最多 3 次
                .foregroundMaxRetries(0) // 首次失败后不再占用前台线程
                .backoff(Backoffs.fixed(200))
                .build();
    }

    private static final class NotifyAgainHandler implements StringRecoveryHandler {
        private final CountDownLatch recovered;

        private NotifyAgainHandler(CountDownLatch recovered) {
            this.recovered = recovered;
        }

        @Override
        public String taskName() {
            return "pay-notify"; // 必须与提交时的 taskType 相同
        }

        @Override
        public void recover(String payload, RecoveryContext context) {
            System.out.println("recovered payload=" + payload
                    + ", attempt=" + context.getAttempt());
            recovered.countDown();
        }
    }
}
```

你应该先看到 `accepted taskId=...`，随后看到：

```text
recovered payload=order-1001, attempt=2
```

`attempt=2` 表示这是整条链路的第二次尝试；前台失败算第 1 次，后台恢复算第 2 次。若后台也持续失败，它会按退避时间继续，直到次数耗尽后落为终态失败。

### 结果分支

`ManagedSubmitResult` 有五种结果。入门时先记住前三种：

| 结果 | 含义 | 你通常做什么 |
| :--- | :--- | :--- |
| `Completed` | 前台成功 | 使用 `getValue()` |
| `Accepted` | 前台预算耗尽，任务已交给后台 | 记录 `getTaskId()` 或直接返回“处理中” |
| `Failed` | 策略判定不再重试 | 处理 `getError()` |
| `Existing` | 幂等键命中已有任务 | 读取当前状态，不重复提交 |
| `Rejected` | 存储等基础设施无法接受任务 | 记录日志并按系统故障处理 |

完整分支可查看 [托管持久化重试](retry-managed.md)。

## 部署到多进程使用 JDBC

Memory 只存在当前 JVM 里。多个服务进程要共享任务、互相接管，使用 `team4u-lease-jdbc`：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-jdbc</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

MySQL 建表脚本在仓库中的位置：

```text
team4u-lease/team4u-lease-jdbc/src/main/resources/schema/lease_task_mysql.sql
```

创建表后，把示例中的后端替换为：

```java
import com.team4u.framework.lease.jdbc.JdbcLeaseBackend;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;

import javax.sql.DataSource;

ManagedRetryRuntime runtime = ManagedRetryRuntime
        .lease(new JdbcLeaseBackend(dataSource, new MySqlLeaseDbDialect()))
        .registry(registry)
        .autoScanRecoveryHandlers(false)
        .defaultPolicy(retryPolicy())
        .start();
```

其余 handler、提交代码、结果处理都和 Memory 示例相同。使用 JDBC 后，任务记录会跨进程保留；进程重启后未完成任务可以由后台 Worker 接管。

## 下一步

- 想了解同步、异步和异常匹配：[INLINE](retry-inline.md)
- 想了解后台接管、进程崩溃恢复和生产配置：[MANAGED](retry-managed.md)
- 想选择固定、递增或指数等待：[退避策略](retry-strategy.md)
- 想用注解隐藏模板代码：[注解与代理](retry-proxy.md)、[Spring 整合](retry-spring.md)
- 想看业务场景：[实战案例](retry-sample.md)
