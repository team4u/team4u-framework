# 流程治理：Policy 策略、Retry 重试与 Timeout 控制

在生产级分布式应用中，业务步骤往往面临突发流量、下游不稳定、慢调用与偶发性故障。`team4u-flow` 提供了企业级的治理控制原语，包括无状态网关拦截（`Policy`）、状态持久化策略（`PersistentPolicy`）、自适应重试（`Retry`）与超时控制（`Timeout`）。

---

## 治理架构与拦截模型

治理控制通过 `CONTROL` 节点自外向内包裹业务子流程，形成洋葱圈式的拦截调用链：

```mermaid
graph TD
    subgraph "治理拦截洋葱模型 (Onion Interception)"
        IN["输入数据 Input"] --> P_BEFORE["Policy.before (前置网关评估)"]
        P_BEFORE -->|"Gate.proceed()"| R_LOOP["Retry 循环 (重试控制器)"]
        P_BEFORE -->|"Gate.reject()"| OUT_REJ["直接输出 Rejected(Reason)"]
        P_BEFORE -->|"Gate.fail()"| OUT_FAIL["直接输出 Failed(Failure)"]
        
        R_LOOP --> T_SCOPE["Timeout 作用域时限监控"]
        T_SCOPE --> OP["核心业务 Operation.execute"]
        OP --> T_SCOPE
        
        T_SCOPE -->|返回 Failed 且可重试| R_BACKOFF["退避等待 Backoff"]
        R_BACKOFF --> OP
        
        T_SCOPE -->|最终完成| P_AFTER["Policy.after (后置统计与审计)"]
        P_AFTER --> RES["最终输出 Outcome"]
    end
```

---

## 1. 无状态网关策略：`Policy<K>`

`Policy<K>` 是通用的无状态切面拦截契约，适用于限流、黑白名单校验、权限鉴权、动态开关与指标埋点。

### 接口定义

```java
public interface Policy<K> {
    /**
     * 前置评估网关：在目标流程执行前调用。
     *
     * @param context 策略上下文（包含 flowId、invocationId 等）
     * @param key     从输入中提取的策略路由键
     * @return Gate 判定结果：放行（proceed）、业务拒绝（reject）或系统故障（fail）
     */
    Gate before(PolicyContext context, K key);

    /**
     * 后置通知回调：在目标流程执行完成后调用（无论成功、拒绝、跳过或失败）。
     *
     * @param context    策略上下文
     * @param key        策略路由键
     * @param completion 完成摘要（包含四态 kind、耗时等）
     */
    default void after(PolicyContext context, K key, Completion completion) { }
}
```

### `Gate` 三态判定

- `Gate.proceed()`：放行，继续执行目标业务流程；
- `Gate.reject(Reason.of("BLACKLIST", "用户已被列入黑名单"))`：拦截并直接以 `Rejected` 短路退出，不执行后续业务，亦不触发系统重试；
- `Gate.fail(Failure.of("RATE_LIMIT_EXCEEDED", "触发系统级限流熔断"))`：拦截并直接以 `Failed` 退出，可触发上层容灾恢复。

### 示例：自定义风控校验策略

```java
public class UserRiskPolicy implements Policy<String> {

    @Autowired
    private RiskService riskService;

    @Override
    public Gate before(PolicyContext context, String userId) {
        if (riskService.isBlacklisted(userId)) {
            return Gate.reject(Reason.of("USER_BLOCKED", "该账户处于风险冻结状态"));
        }
        return Gate.proceed();
    }

    @Override
    public void after(PolicyContext context, String userId, Completion completion) {
        // 记录调用审计日志或上报指标
        log.info("Policy after: user={}, outcome={}, duration={}ms",
                userId, completion.kind(), completion.durationMs());
    }
}
```

### DSL 绑定

```java
Flow<OrderRequest, Receipt> protectedFlow = flow
        .policy(UserRiskPolicy.class, OrderRequest::getUserId);
```

---

### 开箱即用限流适配：`team4u-flow-ratelimiter`

为了保持 `team4u-flow` 内核的极简与零外部冗余依赖，流程限流能力由独立的桥接适配模块 **`team4u-flow-ratelimiter`** 提供。该模块将 Flow 的 `Policy<K>` 契约与框架的分布式限流引擎 [`team4u-ratelimiter`](../ratelimiter/README.md) 无缝融合。

#### 1. 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-ratelimiter</artifactId>
</dependency>
```

#### 2. 限流决策动作：`RateLimitAction`

| 动作枚举 | 门控返回值 | 适用场景 |
| :--- | :--- | :--- |
| **`RateLimitAction.FAIL`** *(默认)* | `Gate.fail(Failure)` | 系统级限流/排队。配合外层 `flow.retry(...)` 实现退避重试获取令牌。 |
| **`RateLimitAction.REJECT`** | `Gate.reject(Reason)` | 业务级快速失败/降级短路。直接产生 `Rejected` 结果，**绝不发起重试**。 |

#### 3. 编排示例

```java
import com.team4u.framework.flow.ratelimiter.RateLimitPolicy;
import com.team4u.framework.flow.ratelimiter.RateLimitPolicies;
import com.team4u.framework.flow.ratelimiter.RateLimitAction;

// 1. 最简模式：直接指定限流检查点（默认 FAIL 模式，触发重试）
Flow<OrderRequest, Receipt> flow1 = Flow.step(chargeOp)
        .policy(RateLimitPolicy.of("order.charge"), OrderRequest::getUserId);

// 2. 拒绝模式：限流直接短路业务，不重试
Flow<OrderRequest, Receipt> flow2 = Flow.step(chargeOp)
        .policy(RateLimitPolicies.reject("order.charge", OrderRequest::getUserId));

// 3. 高级定制：动态消耗多 Permits + 提取复杂上下文 + 自定义失败诊断
RateLimitPolicy<OrderRequest> customPolicy = RateLimitPolicy.<OrderRequest>builder()
        .point("order.batch")
        .contextExtractor(OrderRequest::getUserId)
        .permitsExtractor(OrderRequest::getItemCount) // 按批量大小动态扣减
        .action(RateLimitAction.FAIL)
        .failureFactory((result, req) -> Failure.of("RATE_LIMIT_EXCEEDED", 
                "下单频次超限，建议等待 " + result.getRetryAfterMillis() + "ms"))
        .build();

Flow<OrderRequest, Receipt> flow3 = Flow.step(chargeOp)
        .retry(Retry.maxAttempts(3).withBackoff(Duration.ofMillis(200)))
        .policy(customPolicy, req -> req);
```

---

## 2. 重试治理：`Retry` 与 `team4u-flow-retry`

`Retry` 治理原语针对 `Failed` 状态提供自动化重试能力。框架支持核心轻量重试（`Retry`）以及基于 **`team4u-flow-retry`** 的高级重试治理策略（多算法退避、条件重试判定、动态规则集成）。

### 1. 基础重试配置（`team4u-flow` 核心）

```java
import com.team4u.framework.flow.api.Retry;
import java.time.Duration;

// 最多执行 3 次（包含首次执行 + 2 次重试），重试固定间隔 200ms
Flow<OrderRequest, Receipt> flow1 = Flow.step(chargeOp)
        .retry(Retry.maxAttempts(3).withBackoff(Duration.ofMillis(200)));
```

---

### 2. 高级重试与退避治理：`team4u-flow-retry`

为了将成熟的重试退避算法体系与配置中心能力引入流程引擎，`team4u-flow-retry` 提供了基于 `Policy<K>` 契约的重试适配器 **`FlowRetryPolicy<K>`** 与工厂工具类 **`FlowRetries`**。

#### 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-retry</artifactId>
</dependency>
```

#### 核心特性与架构能力

1. **多算法退避体系**：无缝接入 `team4u-retry` 的 Backoff 引擎：
   - **固定延迟（Fixed）**：`FlowRetries.fixed(maxAttempts, delayMillis)`
   - **指数退避（Exponential）**：`FlowRetries.exponential(maxAttempts, initialDelay, multiplier, maxDelay)`
   - **随机抖动退避（Full / Equal Jitter）**：`FlowRetries.jitter(maxAttempts, initialDelay, multiplier, maxDelay)`，有效平抑重试风暴。
   - **等差递增（Increment）**：`FlowRetries.of(maxAttempts, Backoffs.increment(initialDelay, stepMillis))`
2. **条件重试判定（`Predicate<Failure>`）与快速短路**：
   - 支持按错误码（`retryOnCodes(...)` / `abortOnCodes(...)`）或自定义谓词（`retryOn(...)`）精准过滤可重试异常；
   - 命中不可重试异常时，下一次尝试前置门控立即返回 `Gate.reject(...)`，触发业务快速短路，**绝不空耗重试额度与阻塞线程**。
3. **动态与命名规则注册**：
   - 支持通过 `FlowRetries.named("policyName")` 自动检索 `NamedRetryPolicyRegistry` 或对接配置中心（`DynamicRetryPolicyRegistry`）实现重试次数与退避参数热更新。

#### 编排使用示例

```java
import com.team4u.framework.flow.retry.FlowRetries;
import com.team4u.framework.flow.retry.FlowRetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;

// 1. 指数退避重试（初始 100ms，2 倍递增，上限 2s，最多尝试 5 次）
FlowRetryPolicy<OrderRequest> expPolicy = FlowRetries.exponential(5, 100, 2.0, 2000);
Flow<OrderRequest, Receipt> flow1 = expPolicy.wrap(Flow.step(chargeOp), Function.identity());

// 2. 随机抖动退避 + 条件重试（只对 TIMEOUT 与 NETWORK_ERROR 重试，其余异常快速失败）
FlowRetryPolicy<OrderRequest> conditionalPolicy = FlowRetryPolicy.<OrderRequest>builder()
        .maxAttempts(4)
        .backoff(Backoffs.exponentialJitter(50, 2.0, 1000))
        .retryOnCodes("TIMEOUT", "NETWORK_ERROR") // 仅对网络超时重试
        .build();

Flow<OrderRequest, Receipt> flow2 = conditionalPolicy.wrap(Flow.step(chargeOp), Function.identity());

// 3. 动态配置规则（按名称动态从注册表或配置中心加载）
FlowRetryPolicy<OrderRequest> namedPolicy = FlowRetries.named("order-charge-retry");
Flow<OrderRequest, Receipt> flow3 = namedPolicy.wrap(Flow.step(chargeOp), Function.identity());

// 4. 标准洋葱圈链式 DSL 写法（等价于 policy.wrap(...)）
Flow<OrderRequest, Receipt> flow4 = Flow.step(chargeOp)
        .policy(conditionalPolicy, Function.identity())
        .retry(conditionalPolicy.toRetry());
```

---

### 3. 关键语义与幂等保证

- **仅对 `Failed` 重试**：若步骤返回 `Rejected`（业务拒绝）或 `Skipped`（弃权跳过），框架认为这是正常业务结论，**绝不发起重试**；
- **条件快速失败（Fast-Fail）**：若步骤返回不可重试的 `Failed`，`FlowRetryPolicy` 将后续尝试转换为 `Rejected`，阻断外层 `Retry` 循环；
- **稳定幂等键（`invocationId`）**：在多次重试过程中，节点上下文的 `context.invocationId()` 保持恒定不变（`flowId:flowVersion:executionId:path`）。外部服务可以安全地使用该 ID 进行幂等防重校验。

---

## 3. 超时控制：`Timeout`

`Timeout` 原语为指定的子流程或作用域施加最大执行耗时上限：

```java
Flow<OrderRequest, Receipt> timedFlow = flow.timeout(Duration.ofSeconds(3));
```

### 执行与中断机制

- **计时范围**：从进入该作用域的一刻起开始计时，覆盖其包含的所有串行步骤、并行分支与重试退避；
- **超时动作**：一旦超过设定时限，框架向正在执行的工作线程发送物理中断信号（`Thread.interrupt()`），并通过协作式令牌将其置为取消；
- **诊断输出**：超时退出后，整体流程产生携带 `FlowDiagnosticCodes.TIMEOUT` 的 `Failed` 状态。

---

## 控制节点的挂载顺序与洋葱嵌套

在流式编排中，链式调用控制方法的先后顺序决定了拦截器的嵌套层级：

### 模式 A：Policy 在外，Retry 在内

```java
flow.policy(myPolicy, keyFn).retry(retryConfig);
```

```text
[Policy.before] -> [Retry 循环开始 -> [业务执行] -> [失败重试] -> Retry 循环结束] -> [Policy.after]
```
- **语义**：Policy 仅在进入重试循环前评估一次；重试多次只触发一次 Policy 评估；常用于**权限鉴权、黑名单拦截**。

### 模式 B：Retry 在外，Policy 在内

```java
flow.retry(retryConfig).policy(myPolicy, keyFn);
```

```text
[Retry 循环开始 -> [Policy.before] -> [业务执行] -> [Policy.after] -> [失败重试] -> Retry 循环结束]
```
- **语义**：每次重试都会重新进入 Policy 评估；常用于**动态令牌桶限流、动态开关检查**（每次重试均需重新获取令牌）。

---

## 关联章节与进一步阅读

- 了解并行分支的汇合与控制：[并行分支与汇合治理](flow-parallel.md)
- 了解 Durable 模式下的持久化策略 `PersistentPolicy`：[Durable 两段式恢复协议与 PersistentPolicy](flow-durable-resume.md)
- 了解 Local 线程池与超时死锁防御：[Local 线程模型与死锁防御机制](flow-threading.md)
- 查阅所有治理失败与中断诊断码：[诊断码体系与故障排查手册](flow-diagnostics.md)
