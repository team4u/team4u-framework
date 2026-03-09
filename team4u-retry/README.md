# [返回总目录](../README.md)

# team4u-retry

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

`team4u-retry` 是 Team4u Framework 的统一重试模块，用来为同步调用、异步调用、注解方法、Spring Bean 提供一致的重试能力。

它不仅支持常规的进程内重试，还支持在本地重试耗尽后将任务移交到后端持久化系统继续恢复执行，适合支付通知、下游调用、消息补偿等“允许重试且需要抗进程故障”的场景。

## 你可以用它做什么

* 对一段同步逻辑做阻塞式重试
* 对 `CompletableFuture` 异步调用做非阻塞重试
* 通过 `@Retryable` 给方法声明式加重试
* 在 Spring 项目中通过 `@EnableRetry` 自动织入重试代理
* 在本地重试耗尽后，把任务移交给后端继续恢复执行
* 从配置中心动态加载和更新重试策略

## 什么时候适合用重试

适合重试的典型场景：

* 网络抖动、超时、短暂不可用
* 下游服务限流后的延迟恢复
* 第三方接口偶发失败
* 消息投递、通知回调、补偿执行

不适合直接重试的场景：

* 参数错误
* 权限错误
* 幂等冲突
* 明确的业务拒绝类异常

## 目录

* [接入方式选择](#接入方式选择)
* [运行模式对照](#运行模式对照)
* [快速开始](#快速开始)
* [执行语义说明](#执行语义说明)
* [核心概念](#核心概念)
* [异常与终止规则](#异常与终止规则)
* [编程式接入](#编程式接入)
* [注解式接入](#注解式接入)
* [注解模式下的持久化快照内容](#注解模式下的持久化快照内容)
* [Spring 接入](#spring-接入)
* [注意事项](#注意事项)
* [FAQ](#faq)
* [策略加载优先级](#策略加载优先级)
* [动态策略配置示例](#动态策略配置示例)
* [完整示例：内置 Backend + Worker](#完整示例内置-backend--worker)
* [完整示例：Spring Boot 接入](#完整示例spring-boot-接入)
* [核心类与执行流程](#核心类与执行流程)

## 接入方式选择

本项目按使用场景拆分为 4 个模块：

| 场景                                    | 推荐模块                         | 说明                                 |
| --------------------------------------- | -------------------------------- | ------------------------------------ |
| 仅需要在代码里手动包裹重试逻辑          | `team4u-retry-core`              | 适合工具类、基础组件、非 Spring 场景 |
| 希望通过注解给接口 / 类方法增加重试能力 | `team4u-retry-proxy`             | 基于代理增强 `@Retryable` 方法       |
| Spring 项目中自动识别 `@Retryable`      | `team4u-retry-spring`            | 通过 `@EnableRetry` 自动织入         |
| 需要在进程退出、服务重启后继续恢复重试  | `team4u-retry-lease-integration` | 提供基于 Lease 的持久化重试后端      |

> 一般建议：
>
> - 纯 Java 项目：优先使用 `core`
> - 需要声明式重试：使用 `proxy`
> - Spring 项目：使用 `spring`
> - 对“失败后可恢复执行”有要求：结合 `lease-integration`

## 运行模式对照

| 模式                 | 是否需要持久化适配器 | 是否需要 payloadBuilder | 本地耗尽后     | 调用方看到什么        | 是否需要 Worker |
| -------------------- | -------------------- | ----------------------- | -------------- | --------------------- | --------------- |
| 纯内存同步/异步      | 否                   | 否                      | 直接结束       | 最终业务异常          | 否              |
| 持久化降级（编程式） | 是（`RetryBackend`） | 是                      | handoff 到后端 | RetryHandoffException | 是              |
| 持久化降级（注解式） | 是（`RetryBackend`） | 否（框架快照）          | handoff 到后端 | RetryHandoffException | 是              |

## 快速开始

### Maven 依赖

编程式最小依赖：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

想要持久化重试（基于 Lease）：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-retry-lease-integration</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 最小完整示例

#### 内存模式

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)
        .build();

Retryer retryer = Retryer.with(policy);

String result = retryer.execute(() -> {
    // 业务逻辑
    return "ok";
});
```

#### 持久化模式

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(5)
        .localAttempts(2)
        .build();

RetryBackend backend = ...;

Retryer retryer = Retryer.builder()
        .policy(policy)
        .retryBackend(backend)
        .build();

String result = retryer.execute(
        "order-submit",
        context -> {
            RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
            snapshot.setTaskId("order-123");
            return snapshot;
        },
        () -> {
            // 业务逻辑
            return "ok";
        }
);
```

> 若本地尝试耗尽但未达到最大尝试次数，框架会抛出 `RetryHandoffException`，表示任务已移交后端继续处理。

### 1）同步重试：最小示例

```java
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.Retryer;
import com.team4u.framework.retry.backoff.Backoffs;

RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)
        .backoff(Backoffs.fixed(200))
        .build();

Retryer retryer = Retryer.with(policy);

String result = retryer.execute(() -> {
    // 调用可能失败的逻辑
    return "ok";
});
```

适合：

* 本地快速重试
* 不需要任务持久化
* 不需要后端恢复

### 2）异步重试：最小示例

```java
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.Retryer;
import com.team4u.framework.retry.backoff.Backoffs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)
        .backoff(Backoffs.fixed(100))
        .build();

Retryer retryer = Retryer.with(policy);

CompletableFuture<String> future = retryer.executeAsync(
        this::asyncRemoteCall,
        scheduler
);
```

说明：

* 这是纯内存异步重试
* 不会阻塞当前线程去 `sleep`
* 通过 `ScheduledExecutorService` 调度下一次尝试

### 3）注解式重试：最小示例

```java
public interface PayService {
    @Retryable(policy = "pay-notify")
    String notifyPay(String orderId);
}
```

如果你不在 Spring 中，可以通过代理工厂接入：

```java
PayService proxy = RetryProxyFactory.createProxy(new PayServiceImpl(), null);
```

如果你在 Spring 中，直接配合 `@EnableRetry` 使用即可。

### 4）最小示例：本地重试耗尽后移交后端

当你配置了 `RetryBackend` 时，本地重试次数耗尽后会抛出 `RetryHandoffException` 并将任务移交给后端：

```java
try{
        retryer.execute("pay-notify",ctx ->"{\"orderId\":\"A1001\"}",()->{
        throw new

RuntimeException("downstream timeout");
    });
            }catch(
RetryHandoffException ex){
        // 表示前台本地尝试已经结束，但任务已交给后端继续恢复
        // 这个异常不是“彻底失败”，而是“托管权转移”
        }
```

## 执行语义说明

### `maxAttempts` 是什么？

`maxAttempts` 表示**总执行次数上限**，包含首次执行，不是“失败后的额外重试次数”。

例如：

- `maxAttempts = 1`：只执行一次，不重试
- `maxAttempts = 3`：最多执行 3 次（首次 + 最多 2 次重试）

### `localAttempts` 是什么？

`localAttempts` 表示**前台 / 当前进程内最多尝试次数**。

它仅在**持久化重试模式**下有意义：

- 当未配置持久化后端时，所有尝试都在当前进程内完成
- 当配置了持久化后端时：
  - 若失败次数仍在 `localAttempts` 范围内，则继续本地重试
  - 若超出 `localAttempts`，但还未达到 `maxAttempts`，则任务会移交给后端继续调度执行

### 什么是 handoff？

当本地尝试次数耗尽，但任务仍可继续重试时，框架会把任务移交给持久化后端，此时会抛出 `RetryHandoffException`。

请注意：

- `RetryHandoffException` **不代表最终失败**
- 它表示：**前台执行已结束，任务已交由后端继续处理**
- 如果你的系统接入了告警，请避免把它误判为业务最终失败

## 核心概念

理解这个模块，先把 4 个概念分清楚：

### 1. `RetryPolicy`

`RetryPolicy` 用来定义两件事：

* 是否继续重试
* 下一次等多久

常用配置：

* `maxAttempts(int)`：总尝试次数，包含第一次调用
* `localAttempts(int)`：持久化模式下，当前进程内最多尝试多少次
* `maxAttempts(-1)`：无限重试
* `backoff(Backoff)`：退避策略
* `retryOn(...)`：仅对指定异常重试
* `abortOn(...)`：命中后立即终止
* `condition(String)`：使用表达式做进一步控制

示例：

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(5)
        .retryOn(java.io.IOException.class)
        .abortOn(IllegalArgumentException.class)
        .backoff(Backoffs.exponentialJitter(100, 2.0, 3000))
        .condition("message contains 'timeout'")
        .build();
```

补充一点当前默认行为：

* 纯内存模式下，`localAttempts` 默认等于 `maxAttempts`
* 持久化模式下，如果不显式设置 `localAttempts`，当前实现默认只在前台尝试 2 次

### 2. `Backoff` (退避策略)

`Backoff` 决定每次失败后等待多久再重试。统一通过 `Backoffs` 门面类进行创建。

#### 快捷创建方式

内置算法：

* `Backoffs.fixed(delay)`：固定间隔
* `Backoffs.increment(initial, step)`：线性递增
* `Backoffs.exponential(initial, multiplier, maxDelay)`：指数退避
* `Backoffs.exponentialJitter(initial, multiplier, maxDelay)`：指数退避 + 抖动

高并发失败场景推荐优先使用：

```java
Backoffs.exponentialJitter(...)
```

#### 流式 Builder 接入 (推荐)

对于更复杂的参数控制或更好的代码可读性，推荐使用各策略自带的 Builder：

```java
Backoff backoff = Backoffs.exponentialJitterBuilder()
        .initialDelay(200)
        .multiplier(2.0)
        .maxDelay(5000)
        .build();
```

#### 通用扩展 Builder

支持通过字符串类型加载自定义策略：

```java
Backoff backoff = Backoffs.builder("myCustomType")
        .param("foo", 1)
        .param("bar", "value")
        .build();
```

这样可以减少大量请求同时重试带来的“扎堆”问题。

#### 注册与扩展自定义策略

退避策略通过 `BackoffRegistry` 进行管理，它支持自动扫描和手动注册。

1. **实现工厂类**：实现 `BackoffFactory` 接口，并指定一个唯一的 `key()`。
2. **自动注册**：框架会自动扫描类路径下所有 `BackoffFactory` 的实现。

```java
public class MyBackoffFactory implements BackoffFactory {
    @Override
    public String key() {
        return "myCustomType";
    }

    @Override
    public Backoff create(BackoffConfig config) {
        // 从 config.getParams() 中获取参数并还原策略
        return new MyBackoff();
    }
}
```

之后，你就可以在配置中通过 `"type": "myCustomType"` 使用它，或者在代码中通过 `Backoffs.builder("myCustomType")` 进行构建。


## 异常与终止规则

| 情况                                                    | 是否重试         | 说明                                     |
| ------------------------------------------------------- | ---------------- | ---------------------------------------- |
| 命中 `retryOn`                                          | 是               | 按策略继续                               |
| 命中 `abortOn`                                          | 否               | 立即终止                                 |
| `CompletionException` / `ExecutionException` 等包装异常 | 看根因           | 框架会先解包                             |
| `InterruptedException`                                  | 否               | 立即终止并恢复中断标记                   |
| `Error`                                                 | 否               | 直接透传                                 |
| 持久化模式下本地预算耗尽                                | 不在当前线程继续 | 交给后端，前台抛 `RetryHandoffException` |

### 3. 内存重试 vs 持久化重试

这是最关键的语义区别。

#### 内存重试

* 所有重试都发生在当前进程内
* 简单、轻量、接入最快
* 进程挂掉后不会继续重试

#### 持久化重试

* 执行前先把任务意图写入后端
* 当前进程先尝试一部分
* 本地尝试耗尽后，任务移交后端继续恢复执行
* 更适合需要抗宕机的场景

### 4. 恢复执行

当任务被移交到后端后，后端 Worker 会根据 `taskType` 找到对应的 `RecoveryHandler`，然后使用保存下来的 `payload` 或快照继续恢复执行。

可以把它理解成：

* 调用侧负责首次执行和本地重试
* 后端负责任务托管和后续恢复

## 编程式接入

### 同步内存重试

```java
Retryer retryer = Retryer.with(policy);

String result = retryer.execute(() -> doBusiness());
```

这是最简单的方式，只适用于纯内存模式。

### 支持后端降级的同步重试

```java
Retryer retryer = Retryer.builder()
        .policy(policy)
        .retryBackend(RetryBackend)
        .build();

String result = retryer.execute(
        "pay-notify",
        context -> "{\"orderId\":\"A1001\"}",
        this::doBusiness
);
```

三个参数分别表示：

* `taskType`：任务类型，告诉后端“这是什么任务”
* `payloadBuilder`：告诉后端“恢复这个任务需要哪些数据”
* `task`：当前线程内真正执行的业务逻辑

这里的 `payloadBuilder` 不是为了构造业务入参，而是为了生成一份可恢复的快照。

例如支付通知失败后要交给后端继续处理，至少要能知道是哪笔订单：

```java
context ->"{\"orderId\":\"A1001\"}"
```

否则后端只能知道“有个 pay-notify 任务失败了”，却不知道该恢复哪一条业务。

### `RetryPayloadContext` 是什么

`payloadBuilder` 会收到一个 `RetryPayloadContext`，用于告诉你当前处于哪个阶段。

当前代码里你会稳定拿到的阶段是：

* `PREPARE_INTENT`：任务执行前，准备写入后端意图

枚举中还定义了：

* `HANDOFF_TO_BACKEND`：本地重试耗尽，正式移交后端

可用信息：

* `getPhase()`：当前阶段
* `getExecutedAttempts()`：已经执行过多少次

但当前默认执行路径中，`payloadBuilder` 实际只会收到一次 `PREPARE_INTENT` 回调，所以大多数场景直接返回固定 payload 即可：

```java
context ->"{\"orderId\":\"A1001\"}"
```

如果后续持久化实现扩展为在 handoff 阶段再次构建快照，再利用 `HANDOFF_TO_BACKEND` 分支即可。

### 异步非阻塞重试

```java
CompletableFuture<String> future = retryer.executeAsync(
        "pay-notify",
        context -> "{\"orderId\":\"A1001\"}",
        this::asyncRemoteCall,
        scheduler
);
```

特点：

* 不阻塞当前线程
* 下一次尝试由 `ScheduledExecutorService` 调度
* 调度器异常关闭时，`future` 会异常完成，不会悬挂

## 注解式接入

### 基础用法

```java
public interface PayService {
    @Retryable(policy = "pay-notify")
    String notifyPay(String orderId);
}
```

通过代理工厂创建代理：

```java
PayService proxy = RetryProxyFactory.createProxy(new PayServiceImpl(), RetryBackend);
```

### `@Retryable` 参数说明

- `policy`：重试策略标识，对应策略注册表中的 Key

> **注意**：在注解模式下，框架固定使用 `DEFAULT_PROXY_RECOVERY` 恢复链路，并基于方法调用快照（如 bean、方法名、参数类型、参数值等）自动完成恢复。

## 注解模式下的持久化快照内容

在 `@Retryable` 的持久化重试模式下，框架默认不是简单保存一个字符串 payload，而是会基于方法调用现场生成可恢复的任务快照。

典型快照内容包括：

- Spring / Bean 容器中的 `beanName`
- 调用的 `methodName`
- 参数类型列表 `argTypes`
- 参数序列化结果 `argJsonValues`
- `taskType` (固定为 `DEFAULT_PROXY_RECOVERY`)
- 当前策略相关信息（如最大尝试次数）
- 自动生成或后端分配的 `taskId`

恢复执行时，后台 Worker 会根据这些信息重新定位方法并恢复调用。

### `@RetryIgnore`

如果某个参数不适合进入快照（例如请求上下文、流对象、不可序列化对象、大对象），可以在参数上使用 `@RetryIgnore`，框架会在构建快照时跳过该参数。


## Spring 接入

如果你已经在 Spring 中，推荐这个方式，接入成本最低。

### 1）开启自动代理

```java
@Configuration
@EnableRetry
public class RetryConfig {
}
```

### 2）注册策略

```java
RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
    @Override
    public String key() {
        return "pay-policy";
    }

    @Override
    public RetryPolicy getPolicy() {
        return RetryPolicy.builder()
                .maxAttempts(3)
                .backoff(Backoffs.fixed(100))
                .build();
    }
});
```

如果同一个策略同时存在：

* 静态注册
* 动态配置中心

则运行期优先使用动态配置。

### 3）在 Bean 上使用

```java
@Service
public class PayServiceImpl {

    @Retryable(policy = "pay-policy")
    public String notifyPay(String orderId) {
        return "ok_" + orderId;
    }
}
```

> ⚠️ 注意
> 在 Spring 中提供 `RetryBackend` 只代表允许持久化降级。
> 如果没有同时启动后端 Worker 并注册对应 `RecoveryHandler`，
> 任务虽然会成功入队，但不会被恢复执行。

### 4）Spring AOP 边界

这个模块遵循标准 Spring AOP 规则，因此要注意：

* 同一个 Bean 内部自调用通常不会经过代理
* `final` 类 / `final` 方法不适合依赖类代理增强
* 与 `@Transactional`、日志、监控等多个 Advisor 共存时，生效顺序取决于代理链顺序

如果你要求“自调用也能触发重试”，建议把待重试逻辑拆到独立 Bean，或者改用编程式接入。

## 注意事项

### 1. 内存模式与持久化模式的 API 不同

当配置了持久化后端后，不能再使用仅适用于内存模式的简化 API，例如：

- `Retryer.execute(Callable<T>)`
- `Retryer.executeAsync(Supplier<CompletableFuture<T>>, ScheduledExecutorService)`

此时应使用带 `taskType` 和 `payloadBuilder` 的持久化模式 API。

### 2. 持久化模式要求可生成有效 `taskId`

若后端在 `prepare` 阶段未能为任务生成或补全 `taskId`，框架会快速失败并抛出异常。

### 3. Spring 环境下推荐开启 `@EnableRetry`

在 Spring 项目中，建议通过 `@EnableRetry` 开启自动重试支持，由框架自动注册代理与生命周期管理逻辑。

### 4. 恢复执行过程中不会重复进入重试增强链路

框架内部会使用恢复执行上下文标记，避免任务在恢复执行时再次被代理层重复包装。

### 5. 异步重试依赖调度线程池

异步执行依赖 `ScheduledExecutorService` 进行延迟调度；如未显式指定清理执行器，框架会使用全局执行器完成关闭和清理动作。

## FAQ

### `maxAttempts(3)` 是重试 3 次还是总共 3 次？

包含首次调用在内，共 3 次（即最多额外重试 2 次）。

### `RetryHandoffException` 是不是表示任务彻底失败？

不是。

在纯内存模式下，重试耗尽通常意味着当前调用结束并向上抛出最终异常。
但在配置了 `RetryBackend` 的持久化模式下，当前线程的本地尝试预算耗尽后，
框架会把任务移交给后端系统继续恢复执行，此时前台抛出的
`RetryHandoffException` 表示“当前线程不再继续重试”，
而不是“整个任务生命周期已经彻底失败”。

### 为什么恢复阶段不会再次进入重试代理？

因为后端恢复调用的语义是“基于已保存快照执行一次补偿恢复”，
不是“重新从业务入口再走一整遍调用侧重试托管流程”。
这样可以避免重复预写 intent、重复入队和恢复过程递归套娃。

### 为什么加了 `@RetryIgnore` 后恢复时拿不到这个参数？

因为标记了 `@RetryIgnore` 的参数不会被序列化到快照中。恢复阶段是反序列化快照后通过反射调用的，缺失的参数会以 `null` 传入。

### 为什么 Spring 里自调用没有触发重试？

这是 Spring AOP 的标准限制。代理对象只在外部调用时生效，类内部方法直接调用 `this.xxx()` 会绕过代理。
建议将重试方法移到另一个 Bean，或者通过 `AopContext.currentProxy()` 拿到代理对象调用。

### `Error` 会触发重试吗？

不会。例如 `OutOfMemoryError` 等 `Error` 类错误会直接透传，不做无意义重试。

### 线程中断（`InterruptedException`）后会继续重试吗？

不会。遇到 `InterruptedException` 时，框架会立即停止后续重试，恢复线程中断标记并抛出异常。

### 为什么任务成功后，后端依然显示处于进行中？

持久化模式下的清理（`delete` call）通常是异步的。业务成功返回与后端状态更新之间可能存在极短的时间差，因此后端恢复逻辑必须具备幂等性。

### 应用关闭时需要注意什么？

在非 Spring 场景下，建议显式调用 `RetryExecutorManager.global().shutdown()` 以优雅关闭内置线程池。

### 模式语义

当前有两种运行模式：

#### 1）无 `RetryBackend`

* 仅在当前进程内重试
* 速度快
* 不抗进程故障

#### 2）有 `RetryBackend`

* 任务执行前先记录一条 intent
* 当前进程先做有限次尝试
* 本地尝试耗尽后通过 `handoff(taskId, delay)` 交给后端

这里要特别注意：

> “至少一次”指的是任务意图至少被可靠记录一次，并不等价于“业务只会执行一次”。

所以你的业务恢复逻辑应该具备幂等性。

### `payload`、`intent`、`taskId` 的区别

这三个概念很容易混。

* `payload`：恢复任务所需的数据快照
* `intent`：系统已经记录并接管这次任务的事实
* `taskId`：这条持久化任务的唯一标识

可以简单理解为：

* `payload` 回答“后端拿什么来恢复”
* `intent` 回答“系统是否已经正式托管这次任务”

### `RetryBackend` 的职责

当前 `Retryer` 对接的是 `RetryBackend`，它承担持久化能力：

```java
public interface RetryBackend {

    void prepare(RetryTaskSnapshot snapshot);

    void handoff(String taskId, long delayMillis);

    void saveProgress(RetryTaskSnapshot snapshot);

    void close(String taskId, RetryCloseRequest request);
}
```

语义上：

* `prepare(...)`：执行前预写任务意图；已有 `taskId` 时也可用于补充最新快照
* `handoff(...)`：本地重试预算耗尽后，正式激活后端恢复链路
* `saveProgress(...)`：后端恢复失败但仍需继续重试时，保存执行进度
* `close(...)`：关闭该重试任务，并显式声明结束结果

`RetryCloseRequest` 现在把“怎么结束”拆开表达：

* `outcome`：`SUCCEEDED / FAILED / CANCELLED`
* `reason`：失败时的具体原因
* `errorMessage`：可选错误摘要

### 基于 Lease 的内置适配

如果你已经在项目中使用 `team4u-lease` 模块，可以直接利用内置适配器实现持久化重试，无需额外开发。

主要组件：

* **`LeaseRetryBackend`**：基于 Lease 的重试后端实现。它会将重试任务快照（`RetryTaskSnapshot`）透明地持久化到 Lease 的 payload 中。
* **`RetryLeaseWorker`**：配套的后台 Worker。它负责监听指定队列并自动驱动后续的恢复链路。

特性说明：

* **全场景支持**：编程式接入和注解式接入（`@Retryable`）共用同一套基于 Lease 的恢复逻辑，降低维护成本。
* **状态自动闭环**：`LeaseRetryBackend` 会自动维护任务执行进度。若恢复执行再次失败，它会通过 Lease 的 `release` 机制实现延迟重试；若任务达成终态，则会自动调用 `close` 释放资源。
* **零配置集成**：默认监听名为 `retry-recovery` 的队列。

默认恢复队列：

```text
retry-recovery
```

### 恢复执行：`RecoveryHandler`

后端 Worker 拿到任务后，会按 `taskType` 路由到对应的恢复器：

```java
RecoveryHandlerRegistry.global().register(new RecoveryHandler() {
    @Override
    public String key() {
        return "pay-notify";
    }

    @Override
    public void recover(RetryTaskSnapshot snapshot) {
        String payload = snapshot.getPayload();
        // 反序列化 payload 并继续执行业务
    }
});
```

这里的 `snapshot` 除了业务 `payload`，还会携带：

* `taskId`
* `taskType`
* `executedAttempts`
* `maxAttempts`
* `policyKey`
* `lastError`

因此恢复器可以根据快照做更精细的补偿和日志输出，而不只是拿到一段原始字符串。

### 通用快照恢复器：`SnapshotRecoveryHandler`

如果你的 payload 是注解模式生成的 `RetryTaskSnapshot`，可以直接注册通用恢复器：

```java
RecoveryHandlerRegistry.global().register(
        new SnapshotRecoveryHandler("pay-notify")
);
```

对于默认代理恢复链路，框架还提供了默认任务类型：

```java
RetryTaskTypes.DEFAULT_PROXY_RECOVERY
// team4u.retry.proxy.default-recovery
```

它会自动完成：

1. 反序列化 `RetryTaskSnapshot`
2. 根据 `beanName` 找到目标 Bean
3. 根据 `methodName + argTypes` 定位方法
4. 反序列化参数
5. 调用目标方法继续恢复执行

### 为什么恢复执行不会再次进入重试代理

恢复阶段会通过 `RecoveryExecutionContext` 标记当前线程处于“恢复中”。

代理层检测到该状态后，会直接执行目标方法，而不会再次进入整条重试管线，避免：

* 重复预写 intent
* 重复移交后端
* 恢复调用再次套娃进入重试链路

因此，恢复阶段可以理解为：

* 一次补偿执行
* 而不是重新从入口完整走一遍调用侧托管流程

## 策略加载优先级

框架在解析策略时，按以下顺序查找：

1. 动态策略注册表
2. 静态全局策略注册表

也就是说，若同名策略同时存在，动态策略会覆盖静态策略。

## 动态策略配置示例

动态策略通常以 `retry.policy.` 作为前缀，例如：

```properties
retry.policy.order-submit={
  "maxAttempts"=6,
  "localAttempts"=2,
  "backoff"={
    "type"="exponentialJitter",
    "params"={
      "initialDelay"=500,
      "multiplier"=2.0,
      "maxDelay"=10000
    }
  },
  "retryOnExceptions"=["java.net.SocketTimeoutException", "java.io.IOException"],
  "abortOnExceptions"=["java.lang.IllegalArgumentException"],
  "condition"=""
}=
```

可配置字段包括：

* `maxAttempts`
* `localAttempts`
* `backoff.type`
* `backoff.params`
* `retryOnExceptions`
* `abortOnExceptions`
* `condition`

## 完整示例：内置 Backend + Worker

下面这个例子使用 `team4u-lease-memory` 和 retry 的 lease 集成层，适合本地开发和 demo。

### 1）注册恢复器

```java
import com.team4u.framework.retry.recovery.RecoveryHandler;

public class PayNotifyRecoveryHandler implements RecoveryHandler {
    @Override
    public String key() {
        return "pay-notify";
    }

    @Override
    public void recover(RetryTaskSnapshot snapshot) {
        System.out.println("recover payload = " + snapshot.getPayload());
    }
}
```

### 2）启动 Worker 并执行任务

```java
import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.Retryer;
import com.team4u.framework.retry.backoff.Backoff;
import com.team4u.framework.retry.integration.lease.LeaseRetryBackend;
import com.team4u.framework.retry.integration.lease.RetryLeaseWorker;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;

InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
LeaseRetryBackend retryBackend = new LeaseRetryBackend(backend);

RecoveryHandlerRegistry.global().register(new PayNotifyRecoveryHandler());

RetryLeaseWorker worker = new RetryLeaseWorker(backend, retryBackend);
worker.start("retry-worker");

RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(5)
        .localAttempts(2)
        .backoff(Backoff.exponentialJitter(200, 2.0, 5000))
        .build();

Retryer retryer = Retryer.builder()
        .policy(policy)
        .retryBackend(retryBackend)
        .build();

retryer.execute(
        "pay-notify",
        context -> "{\"orderId\":\"A1001\"}",
        () -> {
            throw new RuntimeException("downstream timeout");
        }
);
```

### 生产建议

* 业务 payload 中尽量带稳定幂等键，而不是只依赖临时上下文
* `payload` 带上版本号
* 后端持久化建议接消息队列、数据库或 Redis 延迟结构
* 恢复逻辑必须具备幂等性
* 如果使用 lease 集成，恢复失败后的再次重试与终态失败会由 `RetryLeaseWorker + LeaseRetryBackend` 协同处理
* 告警、可视化和人工干预仍建议由外部运维系统补上

## 完整示例：Spring Boot 接入

### 1）注册策略

```java

@Configuration
public class RetryPolicyConfig {

    @PostConstruct
    public void registerPolicies() {
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return "pay-policy";
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder()
                        .maxAttempts(3)
                        .backoff(Backoff.fixed(200))
                        .build();
            }
        });
    }
}
```

JDK 8 / Spring 5 项目可使用 `javax.annotation.PostConstruct`。

### 2）开启自动代理

```java

@Configuration
@EnableRetry
public class RetryAutoConfiguration {
}
```

### 3）声明业务服务

```java

@Service
public class PayService {

    private final AtomicInteger counter = new AtomicInteger();

    @Retryable(policy = "pay-policy")
    public String notifyPay(String orderId) {
        if (counter.incrementAndGet() < 3) {
            throw new RuntimeException("temporary failure");
        }
        return "ok_" + orderId;
    }
}
```

### 4）调用效果

```java

@Component
public class DemoRunner implements CommandLineRunner {

    private final PayService payService;

    public DemoRunner(PayService payService) {
        this.payService = payService;
    }

    @Override
    public void run(String... args) {
        String result = payService.notifyPay("A1001");
        System.out.println(result);
    }
}
```

预期效果：

* 前两次抛异常
* 第三次成功
* 调用方无需自己写循环重试逻辑

### 5）如果要启用持久化降级

额外提供一个 `RetryBackend` Bean：

```java

@Configuration
public class RetryBackendConfig {

    @Bean
    public RetryBackend RetryBackend() {
        return new YourRetryBackend();
    }
}
```

然后业务方法声明任务类型：

```java

@Retryable(policy = "pay-policy", taskType = "pay-notify")
public String notifyPay(String orderId) {
    // ...
}
```

对于注解模式，这个适配器需要能够保存完整 `RetryTaskSnapshot` 并在恢复阶段提供给 `SnapshotRecoveryHandler` 或你的自定义
`RecoveryHandler`。

此时还需要配套的 Worker 和 `RecoveryHandler`，否则任务只能入队，无法恢复执行。

## 核心类与执行流程

### 主要类

* `Retryer`：统一执行入口
* `RetryPolicy`：重试策略定义
* `Backoff`：退避算法
* `RetryBackend`：后端持久化抽象
* `RecoveryHandler` / `RecoveryHandlerRegistry`：恢复执行路由
* `@Retryable`：注解式接入
* `@EnableRetry`：Spring 自动代理开关

### 执行流程

```mermaid
graph TD
    A[业务调用] --> B[Retryer 或 RetryInterceptor]
    B --> C[RetryPolicy.canRetry]
    C --> D{还能重试?}
    D -->|是| E[Backoff 计算延迟]
    E --> B
    D -->|否| F{是否存在 RetryBackend}
    F -->|否| G[抛出最终异常]
    F -->|是| H[handoff task 到后端]
    H --> I[抛出 RetryHandoffException]
    I --> J[Worker 拉起恢复任务]
    J --> K[RecoveryHandlerRegistry 路由]
```
