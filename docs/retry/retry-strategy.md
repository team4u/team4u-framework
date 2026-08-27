# 退避策略与动态配置

`team4u-retry` 内置固定、等差、指数与指数抖动退避算法，并支持 LRU 实例缓存与配置中心动态下发。

## 内置退避策略

所有退避策略实现 `Backoff`，可由 `Backoffs` 工厂创建：

| 类型 | 构造方式 | 参数 | 适用场景 |
| :--- | :--- | :--- | :--- |
| `fixed` | `Backoffs.fixed(1000)` | `delay` | 低频调用、固定间隔轮询 |
| `increment` | `Backoffs.increment(500, 200)` | `initialDelay`, `stepMillis` | 渐进式探测服务恢复 |
| `exponential` | `Backoffs.exponential(100, 2.0, 5000)` | `initialDelay`, `multiplier`, `maxDelay` | 防止持续冲垮过载下游 |
| `exponentialJitter` | `Backoffs.exponentialJitter(100, 2.0, 5000)` | `initialDelay`, `multiplier`, `maxDelay` | 高并发推荐，打散重试时间 |

`maxRetries` 不包含首次执行。例如 `maxRetries=3` 表示首次执行失败后最多再重试 3 次，总尝试上限为 4 次。

## 指数抖动算法

无抖动的指数退避会让大量请求在相同时间点再次失败重试，形成重试风暴。`ExponentialJitterBackoff` 使用固定下界随机区间：

1. 计算 `initialDelay * multiplier^(attempt - 1)`，并用 `maxDelay` 封顶；
2. 若上限不大于 `initialDelay`，直接返回上限；
3. 否则在 `[initialDelay, maxCalculatedDelay]` 闭区间内生成随机延迟。

因此每次延迟至少为 `initialDelay`，同时不同进程的重试点会被随机打散。

## BackoffRegistry 与 LRU 缓存

`BackoffRegistry` 按 `(type, params)` 生成缓存 key，容量为 1024 的 LRU 会复用等值配置创建出的 Backoff。内置类型通过 SPI 自动注册。

自定义 Backoff 需要同时提供：

- 稳定的 `type` 标识；
- 可序列化的参数；
- `Backoff.toConfig()`；
- 注册在同 registry 中的 `BackoffFactory`，能根据 `type + params` 重建实例。

## MANAGED 持久化格式

`LeaseRetryRecordSerializer` 的当前 schema 为 `version=1`。Backoff 只保存 `type + params`，不保存 Java 类名或字段布局：

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

内置参数校验是严格的：类型错误、缺失参数或额外参数都会被拒绝。旧 Lease payload 没有 `version=1`，不做兼容迁移。若策略中的异常类型不是 `java.*` Throwable，还必须为 `LeaseRetryRecordSerializer` 构造显式 allowlist，详见[托管持久化重试](retry-managed.md)。

## 动态策略配置

配置中心可使用 `retry.policy.<policyId>` 存放 JSON 规则：

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

解析规则：

- `maxRetries` 与 `foregroundMaxRetries` 都不包含首次执行。
- `foregroundMaxRetries` 只在 MANAGED 中有意义，且不能超过 `maxRetries`。
- `retryOnExceptions` 与 `abortOnExceptions` 会在解析时加载并校验为 `Throwable` 子类；类不存在或类型不匹配会立即失败。
- MANAGED 持久化时，非 `java.*` 异常还必须加入 `LeaseRetryRecordSerializer` allowlist。

## 策略查找优先级

按策略名称查找时：

1. `DynamicRetryPolicyRegistry` 中配置中心下发的动态策略；
2. `NamedRetryPolicyRegistry.global()` 注册的静态策略工厂。

两者都未命中时抛出 `IllegalArgumentException`。
