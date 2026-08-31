# 表达式规则门控策略：`team4u-flow-criterion`

在复杂多变的业务场景中（如营销圈选、动态风控、权限准入、A/B 实验分流等），业务规则往往面临高频变更。若每次规则调整都修改 Java 代码并重新发布服务，会导致开发效率低下且伴随发布风险。

`team4u-flow-criterion` 模块将流程引擎的无状态治理契约 [`Policy<K>`](flow-governance.md#核心治理契约) 与规则表达式引擎 [`team4u-criterion`](../criterion/README.md) 结合，支持直接使用**低开销、高性能的类 SQL 文本表达式**进行前置门控拦截与流程分支判定。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-criterion</artifactId>
</dependency>
```

> [!NOTE]
> `team4u-flow-criterion` 生产代码仅依赖 `team4u-flow` 与 `team4u-criterion`，保持纯净零额外框架污染。

---

## 核心架构与双模态集成

`team4u-flow-criterion` 为 Flow 提供了两套正交且互补的集成形式：

```mermaid
graph TD
    subgraph "team4u-flow-criterion 双模态架构"
        subgraph "模式 A: 前置门控切面 (CriterionPolicy)"
            P_IN["输入 Key"] --> CP_EVAL{"表达式规则判定<br/>(age >= 18 && risk < 60)"}
            CP_EVAL -->|"匹配"| CP_OK["Gate.proceed() 放行"]
            CP_EVAL -->|"不匹配"| CP_REJ["Gate.reject(Reason) 业务短路"]
        end
        
        subgraph "模式 B: 条件分支谓词 (CriterionPredicate)"
            PR_IN["流程数据 Context"] --> PR_EVAL{"表达式匹配<br/>(vip == true && amount > 500)"}
            PR_EVAL -->|"true"| BR_A["执行优惠折扣子流程"]
            PR_EVAL -->|"false"| BR_B["执行普通结算子流程"]
        end
    end
```

---

## 模式 A：门控策略 (`CriterionPolicy<K>`)

用于在节点或子流程执行前进行**准入校验、风控拦截与黑白名单过滤**。

### 基础门控模式速查

| 模式枚举 | 构建方式 | 行为语义 | 典型应用场景 |
| :--- | :--- | :--- | :--- |
| **`PERMIT_IF`** | `CriterionPolicy.builder().expression(expr).mode(PERMIT_IF)` | **满足表达式则放行**；不满足时以 `Rejected` 短路退出。 | **准入许可**：如“年龄满 18 岁且已完成实名认证”。 |
| **`REJECT_IF`** | `CriterionPolicy.builder().expression(expr).mode(REJECT_IF)` | **满足表达式则以 `Rejected` 短路退出**；不满足时放行。 | **风险拦截**：如“处于黑名单中或风险评分超标”。 |
| **`FAIL_IF`** | `CriterionPolicy.builder().expression(expr).mode(FAIL_IF)` | **满足表达式则以 `Failed` 系统故障退出**；不满足时放行。 | **严重故障熔断**：如“探测指标异常，需触发容灾或外层重试”。 |

```java
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.criterion.CriterionPolicy;
import com.team4u.framework.flow.model.Reason;

// 1. 准入放行：未满 18 岁以 UNDERAGE 错误码业务拒绝
CriterionPolicy<UserRequest> underageGuard = CriterionPolicy.<UserRequest>builder()
        .expression("age >= 18")
        .mode(CriterionPolicy.Mode.PERMIT_IF)
        .reasonFactory((ctx, req) -> Reason.of("UNDERAGE", "用户未满 18 周岁"))
        .build();
Flow<UserRequest, Receipt> flow1 = Flow.step(chargeOperation)
        .policy(underageGuard, req -> req);

// 2. 风险拦截：命中黑名单或风控分过高直接短路
CriterionPolicy<UserRequest> riskGuard = CriterionPolicy.<UserRequest>builder()
        .expression("blacklisted == true || riskScore > 80")
        .mode(CriterionPolicy.Mode.REJECT_IF)
        .reasonFactory((ctx, req) -> Reason.of("RISK_BLOCKED", "触发风控拦截"))
        .build();
Flow<UserRequest, Receipt> flow2 = Flow.step(chargeOperation)
        .policy(riskGuard, req -> req);
```

> [!NOTE]
> `CriterionPolicies` 工厂类仅提供 `permitIf` / `rejectIf` / `failIf` 便捷方法，已不再提供
> `builder()` 入口；统一使用 `CriterionPolicy.builder()` 流式构建。

---

### 高级定制：`CriterionPolicy.builder()` 详解

当表达式需要针对复杂嵌套对象、Map 结构进行求值，或者需要在拦截时携带动态诊断上下文（如当前重试轮次 `context.attempt()`、节点路径等）时，使用 Builder 进行定制：

```java
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.criterion.CriterionPolicy;
import com.team4u.framework.flow.criterion.CriterionAction;
import com.team4u.framework.flow.model.Reason;

import java.util.LinkedHashMap;
import java.util.Map;

CriterionPolicy<OrderRequest> customPolicy = CriterionPolicy.<OrderRequest>builder()
        // 1. 声明类 SQL 规则表达式
        .expression("buyer.verified == true && order.totalAmount <= 50000")
        
        // 2. 设定模式：PERMIT_IF (满足放行), REJECT_IF (满足拒绝), FAIL_IF (满足报错)
        .mode(CriterionPolicy.Mode.PERMIT_IF)
        
        // 3. 提取目标计算实体（若入参是包装对象，提取内部用于表达式评估的领域对象）
        .targetExtractor(OrderRequest::getPayload)
        
        // 4. 设定 PERMIT_IF 不匹配时的动作：REJECT（默认业务短路）或 FAIL（系统故障）
        .action(CriterionAction.REJECT)
        
        // 5. 自定义 Reason 诊断工厂：注入业务详情与重试轮次（一次构造携带全部 details）
        .reasonFactory((context, req) -> {
            Map<String, String> details = new LinkedHashMap<String, String>();
            details.put("buyerId", req.getBuyerId());
            details.put("amount", String.valueOf(req.getTotalAmount()));
            details.put("attempt", String.valueOf(context.attempt()));
            details.put("path", context.metadata().path());
            return new Reason("HIGH_VALUE_UNVERIFIED", "大额未认证交易", details);
        })
        .build();

Flow<OrderRequest, Receipt> flow = Flow.step(chargeOperation)
        .policy(customPolicy, req -> req);
```

### Builder 各配置方法核心作用与原理解析

| Builder 配置方法 | 参数类型 | 默认行为 | 核心作用与业务场景 |
| :--- | :--- | :--- | :--- |
| **`expression(String)`** | `String` | **必填**（无默认值） | **规则表达式文本**。<br/>遵循类 SQL 语法（如 `age >= 18 && tags contains 'VIP'`），支持嵌套字段、Map、集合操作。 |
| **`mode(Mode)`** | `CriterionPolicy.Mode` | `Mode.PERMIT_IF` | **门控模式**：<br/>• `PERMIT_IF`：表达式为 true 则放行，false 则拦截；<br/>• `REJECT_IF`：表达式为 true 则以 `Reason` 拒绝，false 则放行；<br/>• `FAIL_IF`：表达式为 true 则以 `Failure` 报错，false 则放行。 |
| **`targetExtractor(...)`** | `Function<K, Object>` | `k -> k`（直接使用入参） | **目标计算实体提取器**。<br/>若策略入参 `K` 是复合信封（如 `RequestEnvelope<UserOrder>`），可通过此函数提取内部用于表达式求值的领域 POJO 或 Map。 |
| **`action(CriterionAction)`** | `CriterionAction` | `CriterionAction.REJECT` | **`PERMIT_IF` 不满足时的动作**：<br/>• `REJECT`：产出 `Outcome.Rejected`（正常业务短路）；<br/>• `FAIL`：产出 `Outcome.Failed`（技术故障，可触发重试）。 |
| **`reasonFactory(...)`** | `BiFunction<PolicyContext, K, Reason>` | 默认生成码为 `CRITERION_REJECTED` 的 `Reason` | **自定义 Reason 工厂（在 REJECT 判定时调用）**。<br/>第一个参数 `PolicyContext` 包含当前节点路径与重试尝试轮次，第二个参数 `K` 为请求对象，便于组装丰富的诊断信息。默认拒绝码由 `CriterionPolicy.DEFAULT_REJECT_CODE` 常量暴露。 |
| **`failureFactory(...)`** | `BiFunction<PolicyContext, K, Failure>` | 默认生成码为 `CRITERION_FAILED` 的 `Failure` | **自定义 Failure 工厂（在 FAIL 判定时调用）**。<br/>用于生成携带系统错误码与排障元数据的 `Failure` 对象。默认失败码由 `CriterionPolicy.DEFAULT_FAILURE_CODE` 常量暴露。 |
| **`criteria(Criteria)`** | `Criteria` | `Criteria.global()` | **规则求值引擎实例**。<br/>可注入自定义注册了业务自定义函数（UDF）或独立缓存的 `Criteria` 实例。 |

---

## 模式 B：条件分支谓词 (`CriterionPredicate<T>`)

`CriterionPredicate<T>` 实现了标准 Java `Predicate<T>` 接口，可在 Flow 的条件判断、路由分发、步骤跳过中无缝复用：

```java
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.criterion.CriterionPredicates;
import com.team4u.framework.flow.criterion.CriterionPredicate;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

// 1. 定义动态匹配规则谓词
CriterionPredicate<Order> vipEligible = CriterionPredicates.of("vip == true && amount >= 200");

// 2. 在业务步骤中实现动态跳过或分支分发
Flow<Order, Receipt> flow = Flow.step((ctx, order) -> 
        vipEligible.test(order) 
                ? Outcome.accepted(applyVipPromotion(order)) 
                : Outcome.skipped(Reason.of("NOT_ELIGIBLE", "不满足 VIP 促销条件"))
);
```

---

## 常用表达式语法速查

`team4u-criterion` 针对 Flow 上下文中的 JavaBean、Map 与集合对象提供开箱即用的高阶语法支持：

### 数值与关系比较
```text
amount >= 100 && score < 60 && status != 'CANCELLED'
```

### 集合与容器包含
```text
tags contains 'VIP' && roles contains all ['AUDITOR', 'MANAGER']
grade in ['A', 'B', 'A+']
```

### 区间范围判断
```text
age between [18, 60] && score between (60, 100]
```

### 空值与存在性检查
```text
address is not null && items is not empty
```

### 正则与通配符
```text
email =~ '.*@team4u\\.com$' && username like 'admin_*'
```

### 概率与哈希灰度分流
```text
userId hash 0.2 // 按用户 ID 稳定 Hash 圈选 20% 流量
```

完整语法手册与自定义算子扩展，请参考 [Criterion 核心文档](../criterion/README.md)。

---

## 性能与表达式预编译缓存

- **预编译机制**：`CriterionPolicy` 与 `CriterionPredicate` 在初始化构建时完成 AST 语法树解析与预编译；
- **运行期零解析开销**：执行期直接针对预编译 AST 进行快速求值，吞吐量达每秒数百万次评估，性能开销极低。

---

## 关联章节与进一步阅读

- [流程治理概览与洋葱模型](flow-governance.md)
- [限流治理策略 (team4u-flow-ratelimiter)](policy-ratelimiter.md)
- [重试与退避治理策略 (team4u-flow-retry)](policy-retry.md)
- [自定义 Policy 扩展开发](policy-custom.md)
- [四态传播与消费机制 (Skipped / Rejected 消费)](flow-propagation.md)
