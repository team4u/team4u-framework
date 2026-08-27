# Spring 整合与生命周期

`team4u-retry-spring` 提供声明式重试 AOP 与容器级线程池生命周期管理。它不会自动创建 `ManagedRetryRuntime` 或 `ManagedRetryClient`；项目需要 MANAGED 时必须显式提供 Bean。

## 开启注解支持

```java
import com.team4u.framework.retry.spring.EnableRetry;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRetry
public class RetrySpringConfig {
}
```

`@EnableRetry` 会导入：

- `RetryAutoProxyRegistrar`：为类级或方法级 `@Retryable` 注册 AOP 代理；
- `RetrySpringConfiguration`：注册 `InlineRetryClient`、容器级 `RetryExecutorManager`、`SpringBeanContainer` 与 `DefaultRecoveryHandlerRegistrar`。

`SpringRetryInterceptor` 懒加载解析 Bean：

- `InlineRetryClient`：容器中优先，缺省回退 `DefaultInlineRetryClient.getInstance()`；
- `ManagedRetryClient`：容器中存在则启用 MANAGED 代理；不存在时 INLINE 正常工作，调用 `MANAGED` 方法会抛出 `IllegalStateException`。

线程池 Bean 使用 `destroyMethod = "shutdown"`，且 `registerShutdownHook=false`，由 Spring 容器管理生命周期，避免误关其他 Context。

## 声明式业务方法

```java
import com.team4u.framework.retry.proxy.RetryMode;
import com.team4u.framework.retry.proxy.Retryable;
import org.springframework.stereotype.Service;

@Service
public class OrderRpcService {

    @Retryable(policy = "order-rpc-policy", mode = RetryMode.INLINE)
    public String queryRemoteOrder(String orderId) {
        return externalFeignClient.query(orderId);
    }

    @Retryable(policy = "pay-notify-policy", mode = RetryMode.MANAGED)
    public void notifyMerchantAsync(String orderId, String payload) {
        externalWebhookClient.post(orderId, payload);
    }
}
```

MANAGED 代理方法必须返回 `void`。策略中的 `foregroundMaxRetries` 必须显式配置；`maxRetries` 与 `foregroundMaxRetries` 都不包含首次执行。

## 接入 MANAGED 运行时

下面的 JDBC 配置会创建 Lease 后端、本地恢复处理器 registry、`ManagedRetryRuntime` 和 `ManagedRetryClient`。`@EnableRetry` 本身不会提供这些 Bean。

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
        return ManagedRetryRuntime.lease(backend)
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

注册语义：

- `ManagedRetryRuntime` 使用传入的本地 registry；这里显式收集 Spring 容器中的 `StringRecoveryHandler`，并注册代理后台回放所需的 `InvocationReplay`。
- Worker start 时会快照 registry 内容；之后再修改 registry 不影响该 Worker。
- 如果业务全走 `@Retryable` 且不想手工注册业务 handler，也可以不提供 registry，让 runtime 的 `autoScanRecoveryHandlers(true)` 只扫描本地 registry 的 ServiceLoader 实现。
- `@EnableRetry` 内部的 `DefaultRecoveryHandlerRegistrar` 会触发 `RecoveryHandlerRegistry.global().autoScan()`。官方 `ManagedRetryRuntime` 不消费这个全局 registry；不要依赖这个副作用注册后台 Worker。
- `ManagedRetryRuntime.close()` 等价于 `shutdown()`；Spring 示例使用 `destroyMethod = "shutdown"`。若需要等待任务结束，可注入 runtime 后调用 `worker().shutdownGracefully(timeout)`。

生产环境中应确保 `RetryTaskWorker` 与能处理对应 task type 的 handler 部署在同一组进程中。初始 intent 默认 5 分钟后对后台可见；进程崩溃或未 handoff 时会自动接管，恢复处理器必须幂等。
