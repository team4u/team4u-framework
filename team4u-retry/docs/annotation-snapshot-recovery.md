# Annotation Snapshot Recovery

本文说明 `team4u-retry` 在注解模式下的持久化降级恢复链路，以及默认 Proxy 恢复处理器的接入方式。

## 背景

当方法使用 `@Retryable` 且 `durability != MEMORY_ONLY` 时，框架不会把原始调用现场直接交给后端，而是先冻结为 `RetryTaskSnapshot`，再交给 `RetryBackend` 持久化或入队。

恢复执行时，常见诉求是：

- 不手写每个方法的参数反序列化逻辑
- 能按 `taskType` 路由恢复
- 恢复调用时不要再次进入注解重试代理，避免重复入队或嵌套重试

为此，框架提供了：

- `SnapshotRecoveryHandler`
- `RecoveryExecutionContext`
- `RetryTaskTypes.DEFAULT_PROXY_RECOVERY`

## 组件说明

### `SnapshotRecoveryHandler`

`SnapshotRecoveryHandler` 是一个通用 `RecoveryHandler` 实现，适合直接消费 `RetryTaskSnapshot`。

它会执行以下步骤：

1. 从 `payload` 反序列化出 `RetryTaskSnapshot`
2. 通过 `BeanManager` 解析目标 Bean
3. 解析方法签名和参数
4. 反射调用目标方法

### `RecoveryExecutionContext`

`RecoveryExecutionContext` 用 `ThreadLocal` 标记当前线程正处于“恢复执行”阶段。

`RetryDelegate` 在进入核心重试逻辑前会检查：

```java
if (retryable == null || RecoveryExecutionContext.isRecovering()) {
    return proceedTask.call();
}
```

这意味着后端回放方法时，即使目标 Bean 仍然挂着 `@Retryable`，也不会再次进入重试代理链路。

## 默认 Proxy 恢复处理器

框架保留了一个默认任务类型：

```java
RetryTaskTypes.DEFAULT_PROXY_RECOVERY
// team4u.retry.proxy.default-recovery
```

规则如下：

- 如果 `@Retryable(taskType = "...")` 显式声明了 `taskType`，仍然优先使用显式值
- 如果 `taskType` 为空且 `durability != MEMORY_ONLY`，框架自动使用 `DEFAULT_PROXY_RECOVERY`
- 如果 `durability == MEMORY_ONLY`，不会走后端恢复，仍保持原有本地语义

该 key 是框架保留值，不建议业务侧复用为其他自定义 `RecoveryHandler`。

## 注册方式

### Spring 场景

开启 `@EnableRetry` 后，默认恢复处理器会自动注册，无需手工调用注册代码。

### 非 Spring Proxy 场景

显式调用：

```java
import com.team4u.framework.retry.proxy.RetryProxyFactory;

RetryProxyFactory.registerDefaultRecoveryHandler();
```

也可以直接调用：

```java
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;

RecoveryHandlerRegistry.ensureDefaultProxyRecoveryHandlerRegistered();
```

## Worker 示例

```java
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;

public class RetryRecoveryWorker {

    public void handle(String taskType, String payload) throws Exception {
        RecoveryHandler handler = RecoveryHandlerRegistry.global()
                .get(taskType)
                .orElseThrow(() -> new IllegalStateException("RecoveryHandler not found. taskType=" + taskType));

        handler.recover(payload);
    }
}
```

## Bean 解析规则

`SnapshotRecoveryHandler` 会优先按快照中的 `beanName` 执行：

1. `BeanManager.getBean(String)`
2. 如果 `beanName` 像类名，再尝试 `Class.forName(beanName)`
3. `BeanManager.getBean(Class)`

因此注解模式下默认快照中的 `beanName` 需要能被 `BeanManager` 解析到。

## 参数恢复规则

- 简单标量类型会按快照中的 JSON 标量恢复
- 复杂对象会按 JSON 对象恢复
- 被 `@RetryIgnore` 标记的参数会被序列化为 `null`

这意味着：

- 被忽略的参数在恢复阶段只能拿到 `null`
- 依赖线程上下文、请求对象、流、连接句柄之类的参数不适合参与恢复

## 使用建议

- `taskType` 作为后端路由键，应稳定且可读
- 恢复方法本身必须具备幂等性
- Worker 仍需自行处理恢复失败后的重试、死信和告警
- 如果 payload 不是 `RetryTaskSnapshot`，不要用 `SnapshotRecoveryHandler`，而是实现自己的 `RecoveryHandler`
- 如果你需要自定义路由，继续显式声明 `@Retryable(taskType = "...")`，默认处理器不会抢占显式值
