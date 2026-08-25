# 退避策略与动态配置

`team4u-retry` 内置了工业级的退避延时算法，支持防风暴抖动、LRU 策略缓存与配置中心动态下发。

---

## 内置退避策略详解

所有退避策略均实现 `Backoff` 接口并提供流式工具类 `Backoffs`：

| 退避类型 | 构造方式 (`Backoffs`) | 核心算法与计算公式 | 适用场景 |
| :--- | :--- | :--- | :--- |
| **固定延迟 (`fixed`)** | `Backoffs.fixed(1000)` | 每次重试固定等待 $T$ 毫秒。 | 单机定时任务、低频调用 |
| **等差递增 (`increment`)** | `Backoffs.increment(500, 200, 5000)` | $T_n = \min(Max, T_0 + (n - 1) \times Step)$，线性阶梯式拉大间隔。 | 渐进式探测服务恢复 |
| **指数退避 (`exponential`)** | `Backoffs.exponential(100, 2.0, 5000)` | $T_n = \min(Max, T_0 \times Multiplier^{n - 1})$，指数级拉大间隔。 | 防止持续冲垮过载下游 |
| **指数抖动 (`exponentialJitter`)** | `Backoffs.exponentialJitter(100, 2.0, 5000)` | 见下文详解，在 $[T_0, T_{exp}]$ 区间内生成均匀随机数。 | **高并发推荐**，防止集群重试风暴 |

---

### 指数抖动算法实现机制 (`ExponentialJitterBackoff`)

在高并发分布式集群中，如果大批量请求在同一秒因网络闪断失败，若采用无抖动的指数退避，所有节点仍将在后续相同的整点秒发起重试，造成下游服务被“重试雷鸣风暴”二次击垮。

`team4u-retry` 采用 **固定下界随机区间算法**（Fixed Lower Bound Random Interval）：

```java
// 1. 计算当前尝试次数的指数上限
long maxCalculatedDelay = (long) Math.min(
        initialDelayMillis * Math.pow(multiplier, attempt - 1),
        maxDelayMillis
);

// 2. 若上限未超过初始延迟，直接返回
if (maxCalculatedDelay <= initialDelayMillis) {
    return maxCalculatedDelay;
}

// 3. 在 [initialDelayMillis, maxCalculatedDelay] 闭区间内生成高性能随机延迟
return ThreadLocalRandom.current().nextLong(
        initialDelayMillis,
        maxCalculatedDelay + 1L
);
```

> [!TIP]
> 该算法保证了每次重试的延迟**至少不低于 `initialDelayMillis`**，同时将集群中不同节点的重试时间点完全随机打散，平滑下游服务的流量毛刺。

---

## 退避策略注册表与 LRU 缓存 (`BackoffRegistry`)

为了避免高频创建重复的 Backoff 对象，`BackoffRegistry` 内部维护了容量为 1024 的 LRU 缓存：

- 根据 `(type, params)` 生成唯一的 `BackoffCacheKey`；
- 相同参数的配置仅在首次创建实例，后续直接从 LRU 缓存复用，实现零垃圾回收开销。

---

## 动态策略配置 (`DynamicRetryPolicyRegistry`)

你可以在配置中心（如 Apollo、Nacos、Consul 或配置数据库）中配置以 `retry.policy.` 为前缀的 JSON 规则，实现**无需发版、秒级生效**的线上重试治理。

### 1. 配置中心 JSON 规范
配置 Key：`retry.policy.order-payment-rpc`

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
    "java.lang.IllegalArgumentException",
    "com.team4u.framework.base.exception.BizException"
  ]
}
```

### 2. 解析与类型安全校验 (`RetryPolicyParser`)
- **异常类加载校验**：`RetryPolicyParser` 在解析 `retryOnExceptions` 与 `abortOnExceptions` 时，通过 `ClassUtil.loadClass` 进行严格校验：
  - 必须为已加载的类且必须继承自 `java.lang.Throwable`；
  - 若类名不存在或类型不匹配，解析期即抛出包含字段名的明确 `IllegalArgumentException`，防止非法配置流入运行期。

---

## 策略查找优先级

当业务代码或代理拦截器按策略名称（如 `@Retryable(policy = "order-payment-rpc")`）查找策略时：

1. **动态注册表 (`DynamicRetryPolicyRegistry`)**：优先从配置中心监听缓存中获取最新解析后的 `RetryPolicy`。
2. **静态注册表 (`NamedRetryPolicyRegistry`)**：若动态配置未命中，回退到通过 `NamedRetryPolicyRegistry.global().register(...)` 在代码启动期注册的静态策略。

