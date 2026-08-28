# 注解与代理模式

代理模式把重试配置放到方法上。你调用代理对象，框架在方法失败时按 `@Retryable` 指定的策略重试。

先明确边界：

- `INLINE`: 支持有返回值方法和 `CompletableFuture` 方法。
- `MANAGED`: 代理拦截只支持 `void` 方法。需要拿到返回值时，直接使用 `ManagedRetries.with(...)`。

## 最小 INLINE 代理

```java
import com.team4u.framework.retry.proxy.Retryable;

public interface PaymentService {

    @Retryable(policy = "pay-query-policy")
    String queryOrder(String orderId);
}
```

非 Spring 环境创建代理：

```java
import com.team4u.framework.retry.inline.DefaultInlineRetryClient;
import com.team4u.framework.retry.proxy.RetryProxyFactory;

PaymentService rawService = new PaymentServiceImpl();
PaymentService service = RetryProxyFactory.createProxy(
        rawService,
        PaymentService.class,
        DefaultInlineRetryClient.getInstance(),
        null);

String body = service.queryOrder("order-1001");
```

`policy` 是策略名。策略来自动态配置或 `NamedRetryPolicyRegistry.global()`。示例注册方式：

```java
import com.team4u.framework.retry.api.NamedRetryPolicyFactory;
import com.team4u.framework.retry.api.NamedRetryPolicyRegistry;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;

NamedRetryPolicyRegistry.global().register(new NamedRetryPolicyFactory() {
    @Override
    public String key() {
        return "pay-query-policy";
    }

    @Override
    public RetryPolicy create() {
        return RetryPolicy.builder()
                .maxRetries(2)
                .backoff(Backoffs.fixed(200))
                .retryOn(IOException.class)
                .build();
    }
});
```

## MANAGED 代理

MANAGED 代理会把方法名和参数快照保存下来，前台失败后由后台重新调用同一个方法。它必须使用 `InvocationReplay` 作为后台 handler type。

```java
import com.team4u.framework.retry.proxy.RetryMode;
import com.team4u.framework.retry.proxy.Retryable;

public interface WebhookService {

    @Retryable(policy = "webhook-policy", mode = RetryMode.MANAGED)
    void notifyMerchant(String orderId, String payload);
}
```

编程式代理需要创建 `ManagedRetryRuntime`，并把 `InvocationReplay` 注册进本地 registry：

```java
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.inline.DefaultInlineRetryClient;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.proxy.InvocationReplay;
import com.team4u.framework.retry.proxy.RetryProxyFactory;
import com.team4u.framework.retry.runtime.lease.ManagedRetryRuntime;

RecoveryHandlerRegistry registry = new RecoveryHandlerRegistry();
registry.register(new InvocationReplay());

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
    WebhookService service = RetryProxyFactory.createProxy(
            rawService,
            WebhookService.class,
            DefaultInlineRetryClient.getInstance(),
            runtime.client());

    service.notifyMerchant("order-1001", "{\"amount\":100}");
} finally {
    runtime.close();
}
```

结果：

- 前台成功：方法正常返回。
- 前台失败且策略允许继续：代理立即返回，后台稍后回放方法。
- 策略判定不可重试：抛出原始异常。
- 存储 rejected：抛出 `IllegalStateException`。

`InvocationReplay.TASK_NAME` 固定为 `ProxyInvocationReplay`。它不是业务自定义的 task type；业务 handler 场景应使用 `ManagedRetries.with(...)` 和自己的 `StringRecoveryHandler`。

## @RetryIgnore

无法序列化或不应该参与幂等键的参数，可以标记 `@RetryIgnore`：

```java
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;

import javax.servlet.http.HttpServletRequest;

public void notifyMerchant(
        String orderId,
        String payload,
        @RetryIgnore HttpServletRequest request) {
    webhookClient.post(orderId, payload);
}
```

规则：

- 后台回放时，被忽略参数为 `null`，方法内部不能读取它。
- 基本类型参数不能标记 `@RetryIgnore`。
- 幂等键按目标类型、方法和参数快照计算；被忽略参数固定按 `null` 参与，不会因为不同 request 实例生成新任务。

## 代理限制

- 目标类和方法不能是 `final`，否则无法可靠拦截或回放。
- MANAGED 代理方法必须返回 `void`。
- 运行后台回放的进程必须能通过 `BeanManager` 找到目标 Bean。
- 后台回放执行的是目标方法本身，不会再次进入重试代理，避免递归重试。
- Spring 环境优先看 [Spring 整合](retry-spring.md)，不需要手工调用 `RetryProxyFactory`。
