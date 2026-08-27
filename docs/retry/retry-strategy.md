# 退避策略

退避策略回答一个问题：**这次失败后，下一次等多久再试？**

`RetryPolicy.maxRetries` 决定最多再试几次；`Backoff` 决定每次之间等多久。`maxRetries` 不包含首次执行，`maxRetries=3` 表示总尝试上限为 4 次。

## 怎么选

| 策略 | 写法 | 第 1/2/3 次重试等待 | 适合 |
| :--- | :--- | :--- | :--- |
| 固定间隔 | `Backoffs.fixed(1000)` | 1000ms / 1000ms / 1000ms | 下游恢复很快，调用频率低 |
| 等差递增 | `Backoffs.increment(200, 300)` | 200ms / 500ms / 800ms | 希望逐步放大间隔 |
| 指数递增 | `Backoffs.exponential(100, 2.0, 5000)` | 100ms / 200ms / 400ms | 下游可能过载，需要快速拉大间隔 |
| 指数 + 随机抖动 | `Backoffs.exponentialJitter(100, 2.0, 5000)` | 每次在 `[100ms, 理论值]` 内随机 | 多实例同时失败，最常用 |

单位都是毫秒。`increment` 的两个参数分别是初始等待和每次增加的步长；第二个参数不是倍率。指数策略的 `multiplier` 是倍率，`maxDelay` 是等待上限。

## 实际示例

固定间隔：适合本地重试或已知 1 秒左右恢复的依赖。

```java
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;

RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(3)
        .backoff(Backoffs.fixed(1000))
        .build();
```

等差递增：第一次等 200ms，之后每次多等 300ms。

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(3)
        .backoff(Backoffs.increment(200, 300))
        .build();
```

指数递增：第一次 100ms，第二次 200ms，第三次 400ms，最多不超过 5 秒。

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(5)
        .backoff(Backoffs.exponential(100, 2.0, 5000))
        .build();
```

指数加抖动：推荐给多进程、高并发场景使用。

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(5)
        .backoff(Backoffs.exponentialJitter(100, 2.0, 5000))
        .build();
```

## 随机抖动在做什么

无抖动的指数退避会让很多请求在同一个时间点重试，例如都等 100ms、200ms、400ms，下游恢复瞬间会被再次打满。

`exponentialJitter` 的计算方式：

1. 先按指数公式算出本次理论等待：`initialDelay * multiplier^(attempt - 1)`。
2. 用 `maxDelay` 封顶。
3. 在 `[initialDelay, 理论等待]` 的闭区间内取随机值。

因此它有一个固定下界：等待不会小于 `initialDelay`。如果理论上限不大于初始值，就直接返回上限。

## 参数校验

常用约束：

- 固定：`delay >= 0`。
- 递增：`initialDelay >= 0`，`stepMillis >= 0`。
- 指数：`initialDelay >= 0`，`multiplier > 0`，`maxDelay >= initialDelay`。

不满足时构建策略会直接失败。

## MANAGED 持久化

MANAGED 会把 Backoff 保存成稳定的 `type + params`，当前记录格式为 `version=1`：

```json
{
  "type": "exponentialJitter",
  "params": {
    "initialDelay": 1000,
    "multiplier": 2.0,
    "maxDelay": 60000
  }
}
```

内置策略包括：

| type | params |
| :--- | :--- |
| `fixed` | `delay` |
| `increment` | `initialDelay`, `stepMillis` |
| `exponential` | `initialDelay`, `multiplier`, `maxDelay` |
| `exponentialJitter` | `initialDelay`, `multiplier`, `maxDelay` |

解析时参数必须类型正确、数量正确，不能多传未知参数。格式不写 Java 类名，旧版 Lease payload 不做兼容迁移。

自定义 Backoff 需要提供稳定 type、可序列化 params、`toConfig()`，以及注册在同一个 `BackoffRegistry` 的 `BackoffFactory`。无法表达为配置的实现不适合默认持久化格式，应自定义 `RetryRecordSerializer`。

## 动态配置（高级）

配置中心可以按策略名下发 JSON：

```json
{
  "maxRetries": 5,
  "foregroundMaxRetries": 1,
  "condition": "retryCount <= 3 && message contains 'timeout'",
  "backoff": {
    "type": "exponentialJitter",
    "params": {
      "initialDelay": 500,
      "multiplier": 2.0,
      "maxDelay": 10000
    }
  },
  "retryOnExceptions": [
    "java.net.SocketTimeoutException",
    "java.io.IOException"
  ],
  "abortOnExceptions": [
    "java.lang.IllegalArgumentException"
  ]
}
```

规则：

- `maxRetries` 与 `foregroundMaxRetries` 都不包含首次执行。
- `foregroundMaxRetries` 只用于 MANAGED，且不能超过 `maxRetries`。
- 异常类会在解析时加载并校验为 Throwable 子类，类不存在或类型不匹配会立即失败。
- MANAGED 持久化非 `java.*` 异常时，还需要加入 serializer allowlist，见[托管持久化重试](retry-managed.md)。

按名称查找策略时，先查动态配置 `DynamicRetryPolicyRegistry`，再查全局静态注册 `NamedRetryPolicyRegistry.global()`；都没有则抛出 `IllegalArgumentException`。
