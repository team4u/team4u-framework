# Spring 整合与生命周期

Spring 模块提供 `@Retryable` 注解拦截和线程池生命周期管理。**它不会自动创建 MANAGED 运行时**：使用 `MANAGED` 时，必须在容器里显式装配 Lease 后端、handler registry 和 `ManagedRetryRuntime`。

## 开启 INLINE

```java
import com.team4u.framework.retry.spring.EnableRetry;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRetry
public class RetrySpringConfig {
}
```

然后使用注解：

```java
import com.team4u.framework.retry.proxy.RetryMode;
import com.team4u.framework.retry.proxy.Retryable;
import org.springframework.stereotype.Service;

@Service
public class OrderRpcService {

    @Retryable(policy = "order-rpc-policy", mode = RetryMode.INLINE)
    public String queryRemoteOrder(String orderId) {
        return externalClient.query(orderId);
    }
}
```

`@EnableRetry` 会注册 INLINE 客户端、容器级调度线程池和 Spring Bean 容器适配器。适配器由 `RetrySpringConfiguration` 显式 `@Import(Team4uBeanConfiguration.class)` 提供；这个配置来自 `team4u-bean-spring`，一个上下文只会注册一个 `SpringBeanContainer`。没有 `ManagedRetryClient` Bean 时 INLINE 正常工作；调用 `MANAGED` 方法会快速失败。

策略可通过动态配置或 `NamedRetryPolicyRegistry` 提供。`maxRetries` 不包含首次执行；INLINE 策略不需要 `foregroundMaxRetries`。

## 显式装配 MANAGED

以下 JDBC 示例装配完整链路。业务 handler 声明为 Spring Bean 后，通过 `ObjectProvider<StringRecoveryHandler>` 收集进本地 registry；代理后台回放还需要 `InvocationReplay`。

```java
import com.team4u.framework.lease.jdbc.JdbcLeaseBackend;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import com.team4u.framework.retry.proxy.InvocationReplay;
import com.team4u.framework.retry.runtime.lease.ManagedRetryRuntime;
import com.team4u.framework.retry.spring.EnableRetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration
@EnableRetry
public class ManagedRetrySpringConfig {

    @Bean
    public LeaseBackend leaseBackend(DataSource dataSource) {
        return new JdbcLeaseBackend(dataSource, new MySqlLeaseDbDialect());
    }

    @Bean
    public RecoveryHandlerRegistry retryRecoveryHandlerRegistry(
            ObjectProvider<StringRecoveryHandler> handlers) {
        RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
        handlers.forEach(handler -> registry.register(handler));
        registry.register(new InvocationReplay());
        return registry;
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public ManagedRetryRuntime managedRetryRuntime(
            LeaseBackend backend,
            RecoveryHandlerRegistry registry) {
        return ManagedRetryRuntime
                .lease(backend)
                .queueName("retry-recovery")
                .registry(registry)
                .autoScanRecoveryHandlers(false)
                .defaultPolicy(RetryPolicy.builder()
                        .maxRetries(5)
                        .foregroundMaxRetries(1)
                        .backoff(Backoffs.exponentialJitter(1000, 2.0, 60_000L))
                        .build())
                .foregroundRecoveryTimeout(Duration.ofMinutes(5))
                .lease(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .heartbeatEnabled(true)
                .heartbeatInterval(Duration.ofSeconds(10))
                .threadName("managed-retry-worker")
                .build();
    }

    @Bean
    public ManagedRetryClient managedRetryClient(ManagedRetryRuntime runtime) {
        return runtime.client();
    }
}
```

创建 MySQL 表：

```text
team4u-lease/team4u-lease-jdbc/src/main/resources/schema/lease_task_mysql.sql
```

MANAGED 注解方法必须是 `void`，策略必须包含 `foregroundMaxRetries`：

```java
@Retryable(policy = "pay-notify-policy", mode = RetryMode.MANAGED)
public void notifyMerchantAsync(String orderId, String payload) {
    webhookClient.post(orderId, payload);
}
```

需要返回值时不要用 MANAGED 代理；改用 `ManagedRetries.with(...)` 并在提交时保存 payload，或直接使用 INLINE。

## 生命周期注意点

- `ManagedRetryRuntime` Bean 使用 `initMethod = "start"` 启动后台 Worker，`destroyMethod = "shutdown"` 停止。
- 需要等待当前任务结束的优雅停机，可注入 runtime 后调用 `runtime.worker().shutdownGracefully(timeout)`。
- Worker 启动时会快照 registry 当前 handler；之后再往 registry 加 Bean 不影响已启动 Worker。
- 不要依赖 `@EnableRetry` 对全局恢复 registry 的自动扫描副作用。`ManagedRetryRuntime` 使用传入的本地 registry。
- 确保运行 Worker 的进程包含对应业务 Bean；否则后台任务取到后会因为缺少目标 Bean 而失败。
- 初始任务默认 5 分钟后对后台可见，用于前台进程崩溃后的自动接管；业务动作必须幂等。
