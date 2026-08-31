# 表达式规则门控策略：`team4u-flow-criterion`

在复杂多变的业务场景中（如营销圈选、动态风控、权限准入、A/B 实验分流），业务人员通常需要动态调整判定规则，而无需频繁修改 Java 代码或重新发布服务。`team4u-flow-criterion` 模块将流程引擎的无状态治理契约 [`Policy<K>`](flow-governance.md#核心治理契约) 与规则表达式引擎 [`team4u-criterion`](../criterion/README.md) 结合，支持直接使用**低开销、高性能的类 SQL 文本表达式**进行前置门控拦截与流程分支路由。

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

## 核心架构与能力双模态

`team4u-flow-criterion` 为 Flow 提供了两套正交且互补的集成形式：

```mermaid
graph TD
    subgraph "team4u-flow-criterion 双模态"
        subgraph "模式 A: 前置门控切面 (CriterionPolicy)"
            P_IN["输入 Key"] --> CP_EVAL{"表达式规则判定<br/>(age >= 18 && risk < 60)"}
            CP_EVAL -->|匹配| CP_OK["Gate.proceed() 放行"]
            CP_EVAL -->|不匹配| CP_REJ["Gate.reject(Reason) 业务短路"]
        end
        
        subgraph "模式 B: 条件分支谓词 (CriterionPredicate)"
            PR_IN["流程数据 Context"] --> PR_EVAL{"表达式匹配<br/>(vip == true && amount > 500)"}
            PR_EVAL -->|true| BR_A["执行优惠折扣子流程"]
            PR_EVAL -->|false| BR_B["执行普通结算子流程"]
        end
    end
```

---

## 门控策略：`CriterionPolicy<K>`

用于在节点执行前进行**准入校验、风控拦截与黑白名单过滤**。

### 门控模式

| 模式枚举 | 便捷工厂方法 | 行为语义 | 适用场景 |
| :--- | :--- | :--- | :--- |
| **`PERMIT_IF`** | `CriterionPolicies.permitIf(expr)` | **满足表达式则放行**；不满足时以 `Rejected` 短路退出。 | **准入许可**：如“年龄满 18 岁且已实名”。 |
| **`REJECT_IF`** | `CriterionPolicies.rejectIf(expr)` | **满足表达式则以 `Rejected` 短路退出**；不满足时放行。 | **风险拦截**：如“处于黑名单中或风险评分超标”。 |
| **`FAIL_IF`** | `CriterionPolicies.failIf(expr, code, msg)` | **满足表达式则以 `Failed` 系统故障退出**；不满足时放行。 | **严重故障熔断**：如“系统探测指标异常，需触发容灾或重试”。 |

### 编排使用示例

```java
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.criterion.CriterionPolicies;
import com.team4u.framework.flow.criterion.CriterionPolicy;

// 1. 准入放行：未满 18 岁以 UNDERAGE 错误码拒绝
Flow<UserRequest, Receipt> flow1 = Flow.step(chargeOperation)
        .policy(CriterionPolicies.permitIf("age >= 18", "UNDERAGE", "用户未满 18 周岁"), req -> req);

// 2. 风险拦截：命中黑名单或风控分过高直接短路
Flow<UserRequest, Receipt> flow2 = Flow.step(chargeOperation)
        .policy(CriterionPolicies.rejectIf("blacklisted == true || riskScore > 80", "RISK_BLOCKED", "触发风控拦截"), req -> req);

// 3. 高级定制：自定义 Reason 工厂与嵌套对象属性提取
CriterionPolicy<OrderRequest> customPolicy = CriterionPolicy.<OrderRequest>builder()
        .expression("buyer.verified == true && order.totalAmount <= 50000")
        .mode(CriterionPolicy.Mode.PERMIT_IF)
        .reasonFactory((ctx, req) -> Reason.of("HIGH_VALUE_UNVERIFIED", "大额未认证交易"))
        .build();

Flow<OrderRequest, Receipt> flow3 = Flow.step(chargeOperation)
        .policy(customPolicy, req -> req);
```

---

## 条件分支谓词：`CriterionPredicate<T>`

`CriterionPredicate<T>` 实现了标准 Java `Predicate<T>` 接口，可在 Flow 的条件判断、路由分发、步骤跳过中无缝复用：

```java
import com.team4u.framework.flow.criterion.CriterionPredicates;
import com.team4u.framework.flow.criterion.CriterionPredicate;

// 定义动态匹配规则
CriterionPredicate<Order> vipEligible = CriterionPredicates.of("vip == true && amount >= 200");

// 在步骤内部实现业务短路与跳过
Flow<Order, Receipt> flow = Flow.step((ctx, order) -> 
        vipEligible.test(order) 
                ? Outcome.accepted(applyVipPromotion(order)) 
                : Outcome.skipped(Reason.of("NOT_ELIGIBLE", "不满足 VIP 促销条件"))
);
```

---

## 常用表达式语法速查

`team4u-criterion` 针对 Flow 上下文中的 JavaBean、Map 与集合对象提供开箱即用的高阶语法支持：

```text
// 数值与关系比较
amount >= 100 && score < 60 && status != 'CANCELLED'

// 集合与容器包含
tags contains 'VIP' && roles contains all ['AUDITOR', 'MANAGER']
grade in ['A', 'B', 'A+']

// 区间范围判断
age between [18, 60] && score between (60, 100]

// 空值与存在性检查
address is not null && items is not empty

// 正则与通配符
email =~ '.*@team4u\\.com$' && username like 'admin_*'

// 概率与哈希灰度分流
userId hash 0.2 // 按用户 ID 稳定 Hash 圈选 20% 流量
```

完整语法手册与自定义算子扩展，请参考 [Criterion 核心文档](../criterion/README.md)。

---

## 关联章节

- [流程治理概览与洋葱模型](flow-governance.md)
- [限流治理策略 (team4u-flow-ratelimiter)](policy-ratelimiter.md)
- [重试与退避治理策略 (team4u-flow-retry)](policy-retry.md)
- [自定义 Policy 扩展开发](policy-custom.md)
- [四态传播与消费机制 (Skipped / Rejected 消费)](flow-propagation.md)
