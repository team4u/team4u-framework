# 限流治理策略：`team4u-flow-ratelimiter`

在微服务与高并发分布式系统中，高成本业务步骤（如第三方支付扣款、外部 RPC 调用、高开销 AI 推理、优惠券核销等）需要实施严格的流量控制与过载保护。

`team4u-flow-ratelimiter` 模块将流程引擎的无状态治理契约 [`Policy<K>`](flow-governance.md#核心治理契约) 与框架的分布式限流引擎 [`team4u-ratelimiter`](../ratelimiter/README.md) 无缝融合，提供开箱即用的流程级分布式限流能力。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-ratelimiter</artifactId>
</dependency>
```

> [!NOTE]
> `team4u-flow-ratelimiter` 遵循纯净与最小依赖原则，生产代码仅依赖 `team4u-flow` 与 `team4u-ratelimiter`，无任何 Spring 或 Jackson 的强制运行时绑定。

---

## 核心架构与决策模型

限流策略作为无状态拦截器，在前置门控阶段（`before`）调用 `team4u-ratelimiter` 引擎尝试获取令牌，并将限流裁决结果转换为 Flow 标准门控决策：

```mermaid
graph TD
    IN["流程输入 Input"] --> EXTR["提取策略路由键 Key 与上下文"]
    EXTR --> ACQ{"RateLimiters.acquire<br/>尝试获取令牌"}
    
    ACQ -->|"放行 (Acquired)"| PROCEED["Gate.proceed()<br/>继续执行目标业务步骤"]
    
    ACQ -->|"超限 & FAIL 动作"| GATE_FAIL["Gate.fail(Failure)<br/>步骤输出 Failed，联动外层重试策略削峰排队"]
    ACQ -->|"超限 & REJECT 动作"| GATE_REJ["Gate.reject(Reason)<br/>步骤输出 Rejected，业务快速短路，绝不重试"]
    
    PROCEED --> OP["目标业务 Operation"]
```

### 限流决策动作：`RateLimitAction`

| 动作枚举 | 门控返回值 | 行为语义 | 典型应用场景 |
| :--- | :--- | :--- | :--- |
| **`RateLimitAction.FAIL`** *(默认)* | `Gate.fail(Failure)` | **系统级限流/排队**：步骤以 `Outcome.Failed` 退出。 | **削峰填谷与排队重试**：配合外层挂载的 `FlowRetryPolicy`，在退避等待后自动重新获取令牌。 |
| **`RateLimitAction.REJECT`** | `Gate.reject(Reason)` | **业务级快速失败/降级短路**：步骤以 `Outcome.Rejected` 退出。 | **防刷拦截与高频降级**：业务直接拒绝请求，**绝不触发多余重试与线程占用**。 |

---

## 编排使用指南与代码示例

### 1. 基础用法（默认 FAIL 模式）

通过 `RateLimitPolicies.of` 绑定限流检查点，默认使用 `FAIL` 模式：

```java
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.ratelimiter.RateLimitPolicies;

// 绑定用户维度的扣款限流检查点：限流时返回 Gate.fail
Flow<OrderRequest, Receipt> flow = Flow.step(chargeOperation)
        .policy(RateLimitPolicies.of("order.charge", OrderRequest::getUserId));
```

### 2. 拒绝模式（快速短路，绝不重试）

当需要对高频恶意流量或超出容量的非关键请求实施即时拒绝时，使用 `RateLimitPolicies.reject`：

```java
// 超过限流阈值时直接返回 Rejected(Reason)，不执行后续业务，亦不触发重试
Flow<OrderRequest, Receipt> flow = Flow.step(chargeOperation)
        .policy(RateLimitPolicies.reject("order.charge", OrderRequest::getUserId));
```

### 3. 高级定制（动态 Permits + 自定义失败诊断）

通过 `RateLimitPolicy.builder()` 支持复杂业务参数提取、动态扣减令牌数与自定义失败信息：

```java
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.ratelimiter.RateLimitPolicy;
import com.team4u.framework.flow.ratelimiter.RateLimitAction;
import com.team4u.framework.flow.model.Failure;

RateLimitPolicy<BatchOrderRequest> customPolicy = RateLimitPolicy.<BatchOrderRequest>builder()
        .point("order.batch")
        .contextExtractor(BatchOrderRequest::getTenantId) // 租户维度路由
        .permitsExtractor(BatchOrderRequest::getItemCount) // 依据批量大小动态扣减令牌
        .action(RateLimitAction.FAIL)
        .failureFactory((result, req) -> Failure.of("RATE_LIMIT_EXCEEDED", 
                "下单过于频繁，建议在 " + result.getRetryAfterMillis() + "ms 后重试")
                .withDetail("tenantId", req.getTenantId())
                .withDetail("requestedPermits", String.valueOf(req.getItemCount())))
        .build();

Flow<BatchOrderRequest, BatchReceipt> flow = Flow.step(batchChargeOperation)
        .policy(customPolicy, req -> req);
```

### 4. 限流与重试联动（削峰填谷排队模式）

将限流策略（`Policy`）作为内层拦截，重试策略（`FlowRetryPolicy`）作为外层控制器。当限流返回 `FAIL` 时，外层重试策略会自动在退避延迟后重新尝试获取令牌：

```java
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.retry.FlowRetries;
import com.team4u.framework.flow.ratelimiter.RateLimitPolicies;

// 链式组合：内层限流（每次重试均重新获取令牌），外层挂载 3 次指数退避重试
Flow<OrderRequest, Receipt> protectedFlow = Flow.step(chargeOperation)
        .policy(RateLimitPolicies.of("order.charge", OrderRequest::getUserId))
        .persistentPolicy(FlowRetries.exponential(3, 100, 2.0, 1000), OrderRequest::getUserId);
```

```text
[请求到来]
  -> [Retry 尝试 1]
    -> [RateLimit.before 尝试获取令牌] -> [超限! 返回 Gate.fail]
  -> [Retry 捕获 Failed -> 计算指数退避 100ms 并等待]
  -> [Retry 尝试 2]
    -> [RateLimit.before 重新获取令牌] -> [成功获取! 返回 Gate.proceed]
    -> [执行 chargeOperation] -> [返回 Accepted(Receipt)]
  -> [流程成功完成]
```

---

## 限流算法与配置驱动热生效

限流引擎支持通过配置中心（`team4u-config`）动态下发限流算法（令牌桶、漏桶、滑动窗口、固定窗口）与阈值配置，无需重启服务即可即时热生效：

```json
// 配置键：team4u.ratelimiter.order.charge
[
  {
    "id": "tb-user-limit",
    "algorithm": "token-bucket",
    "capacity": 10,
    "refillRate": 2.0,
    "key": "${userId}"
  }
]
```

### 支持的限流算法速查

| 算法类型 | 算法标识 | 核心特点 | 适用场景 |
| :--- | :--- | :--- | :--- |
| **令牌桶 (Token Bucket)** | `token-bucket` | 允许应对突发流量，平滑补充令牌 | 核心交易下单、高并发 API 网关 |
| **漏桶 (Leaky Bucket)** | `leaky-bucket` | 恒定速率流出，严格整形流量 | 保护脆弱下游、对外部第三方接口限速 |
| **滑动窗口 (Sliding Window)** | `sliding-window` | 精确统计窗口内请求数，无临界跳变 | 严格周期配额、防高频刷单 |
| **固定窗口 (Fixed Window)** | `fixed-window` | 实现极简、内存开销极低 | 粗粒度防刷、基础防护 |

详细限流算法模型与多维度路由键配置，请参考 [RateLimiter 核心文档](../ratelimiter/README.md)。

---

## 关联章节与进一步阅读

- [流程治理概览与洋葱模型](flow-governance.md)
- [重试与退避治理策略 (team4u-flow-retry)](policy-retry.md)
- [表达式规则门控策略 (team4u-flow-criterion)](policy-criterion.md)
- [自定义 Policy 扩展开发](policy-custom.md)
