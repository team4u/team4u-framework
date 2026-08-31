# 实战案例

> 层级：L1 → L2 → L3 递进 · 前置：quick-start · 模块：team4u-flow（案例一另需 team4u-flow-bean，案例三另需 team4u-flow-durable）

本章给出三个完整可运行风格的示例，按复杂度分层排序：先 L1 纯编排，再 L1 → L2 失败治理，最后 L2 → L3 挂起与崩溃恢复。每个案例开头有"本例用到"清单，可按需跳读。

---

# 1. 导读

| 案例 | 层级 | 核心演示点 | 建议阅读顺序 |
| :--- | :--- | :--- | :--- |
| 案例一：电商履约（Bean 容器集成） | L1 | `Class`/qualifier 声明式编排、`BeanFlows.compile` 编译期装配、`@Transactional` 切面保留、多渠道路由 | 1 |
| 案例二：订单风控路由与降级 | L1 → L2 | `route` 多通道分发、`firstApplicable` 降级链与 Skipped 消费、`retry` + `recoverWith` + `timeout` 失败治理、六通道图 | 2 |
| 案例三：支付审批挂起与 Durable 恢复 | L2 → L3 | `await`/`resume` 挂起恢复、同一份定义切换 Durable 执行器、自定义 `StateMapper`、resume 两段 CAS 崩溃收敛、testkit | 3 |

按画像选读：只要类型化编排，读案例一即可停——它就是"类型安全的 pipeline 库 + 容器装配"，没有并发模型与持久化协议的心智负担；步骤会失败、需要降级重试，加读案例二（前半 L1 路由与降级链，后半 L2 治理）；要挂起等人工审批或进程重启后续跑，读案例三（前半 L2 `await`，后半换 Durable 执行器获得 L3 崩溃恢复）。

---

# 2. 公共代码

三个案例共用以下声明；后续片段省略重复 import。外部服务（黑名单、支付网关、资金服务、人工队列）以桩代替，生产中为真实 RPC/DB 客户端。

```java
import com.team4u.framework.flow.*;        // Flow / Local / LocalExecutable
import com.team4u.framework.flow.api.*;    // Operation / OperationContext / ResumePoint / Retry / Policy / Gate
import com.team4u.framework.flow.model.*;  // Outcome / Reason / Failure / FlowResult / Resumed / Recovery / Suspension
import com.team4u.framework.flow.test.*;   // LocalFixture / DurableFixture / FlowAssertions / OperationStub

final class Blacklist {
    static boolean contains(String userId) { return "u2".equals(userId); }
}

final class PaymentGateway {
    static String autoAccept(String invocationId, OrderRequest order) { return "ok"; }
    static String semiAutoAccept(String invocationId, OrderRequest order) { return "ok"; }
}

final class ManualQueue {
    static void enqueue(OrderRequest order, String note) { }
}

final class FundsService {
    static void freeze(String id, PaymentRequest p) { }
    static void unfreeze(PaymentRequest p) { }
    static String settle(String id, PaymentRequest p) { return "tx-" + p.paymentId; }
}
```

---

# 3. 案例一：电商履约——Bean 容器集成

> 层级：L1 日常层 · 前置：quick-start 2.2 · 模块：team4u-flow + team4u-flow-bean + team4u-bean-spring

**本例用到**

- API：`Flow.step(Class)` / `then` / `Flow.route(Class).caseOf / withoutOtherwise` / `policy(Class)` / `named`、`BeanFlows.compile`
- 痛点：业务步骤依赖容器组件（DAO、RPC 客户端、事务切面）——硬编码实例装配让装配处臃肿，反射式查找又丢失编译期类型安全并付出每次调用的反射开销
- 层：L1（`policy` 挂载属 L2 扩展点，语义见[核心语义](flow-semantics.md)第 5 节；Bean 绑定本身不引入新的流程语义，见 [Bean 容器集成](flow-bean.md)）

## 3.1 业务场景与架构

在典型的企业级 Spring Boot 应用中，业务步骤通常需要依赖容器中的组件（如数据库 DAO、RPC Client、缓存 Client、监控埋点等），并要求支持 Spring 声明式事务（`@Transactional`）与 AOP 代理。本案例演示五件事：所有步骤（`Operation`）与准入策略（`Policy`）均作为 Spring `@Component` 托管注入依赖；流程 DSL 以契约类型与限定符（Bean 名称）声明式编排、不持有物理实例；通过不同限定符实现多渠道仓储策略路由（云仓 `cloudWarehouseOperation` vs 门店前置仓 `localStoreOperation`）；`@Transactional` 事务切面在执行期透明生效；在 `@Configuration` 中通过 `BeanFlows.compile(flow)` 编译期一次性装配为单例 `LocalExecutable`。

## 3.2 业务模型

```java
package com.example.fulfillment.model;

import lombok.Value;

public class FulfillmentModel {

    @Value
    public static class OrderCommand {
        String orderId;
        String userId;
        String sku;
        int quantity;
        FulfillmentType type;
    }

    public enum FulfillmentType { CLOUD, LOCAL_STORE }

    @Value
    public static class FulfillmentReceipt {
        String orderId;
        String trackingNumber;
        String warehouseCode;
    }
}
```

## 3.3 声明 Spring Bean 业务操作与策略

```java
package com.example.fulfillment.operation;

import com.example.fulfillment.model.FulfillmentModel.*;
import com.team4u.framework.flow.api.*;
import com.team4u.framework.flow.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 1. 租户限流与准入 Policy（Spring 托管单例）
@Component
public class FulfillmentRatePolicy implements Policy<String> {

    @Autowired
    private RedisRateLimiter rateLimiter;   // 应用内普通 Spring Bean，实现从略

    @Override
    public Gate before(PolicyContext context, String tenantKey) {
        if (!rateLimiter.tryAcquire(tenantKey, 1)) {
            return Gate.reject(Reason.of("RATE_LIMITED", "租户履约请求超限"));
        }
        return Gate.proceed();
    }
}

// 2. 外部风控校验 Operation（注入 RPC 客户端）
@Component
public class RiskCheckOperation implements Operation<OrderCommand, OrderCommand> {

    @Autowired
    private RiskRpcClient riskRpcClient;    // 实现从略

    @Override
    public Outcome<OrderCommand> execute(OperationContext context, OrderCommand command) {
        // 利用稳定幂等键 invocationId 进行外部调用
        boolean isSafe = riskRpcClient.checkRisk(context.invocationId(), command.getUserId());
        if (!isSafe) {
            return Outcome.rejected(Reason.of("RISK_BLOCKED", "用户命中风控拦截"));
        }
        return Outcome.accepted(command);
    }
}

// 3. 履约渠道路由器 Operation
@Component
public class WarehouseSelectorOperation implements Operation<OrderCommand, FulfillmentType> {
    @Override
    public Outcome<FulfillmentType> execute(OperationContext context, OrderCommand command) {
        return Outcome.accepted(command.getType());
    }
}

// 4a. 云仓履约通道（Bean 名称: cloudWarehouseOperation，支持声明式事务）
@Component("cloudWarehouseOperation")
public class CloudWarehouseOperation implements Operation<OrderCommand, FulfillmentReceipt> {

    @Autowired
    private InventoryService inventoryService;   // 实现从略

    @Override
    @Transactional(rollbackFor = Exception.class) // Spring 事务切面原样保留并生效
    public Outcome<FulfillmentReceipt> execute(OperationContext context, OrderCommand command) {
        String tracking = inventoryService.lockAndDispatchCloud(
                context.invocationId(), command.getOrderId(), command.getSku(), command.getQuantity());
        return Outcome.accepted(new FulfillmentReceipt(command.getOrderId(), tracking, "WH-CLOUD-01"));
    }
}

// 4b. 门店前置仓履约通道（Bean 名称: localStoreOperation）
@Component("localStoreOperation")
public class LocalStoreOperation implements Operation<OrderCommand, FulfillmentReceipt> {

    @Autowired
    private InventoryService inventoryService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Outcome<FulfillmentReceipt> execute(OperationContext context, OrderCommand command) {
        String tracking = inventoryService.lockAndDispatchStore(
                context.invocationId(), command.getOrderId(), command.getSku(), command.getQuantity());
        return Outcome.accepted(new FulfillmentReceipt(command.getOrderId(), tracking, "WH-STORE-99"));
    }
}
```

## 3.4 Spring 容器配置与 Flow 编译

```java
package com.example.fulfillment.config;

import com.example.fulfillment.model.FulfillmentModel.*;
import com.example.fulfillment.operation.*;
import com.team4u.framework.bean.spring.Team4uBeanConfiguration;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.bean.BeanFlows;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(Team4uBeanConfiguration.class) // 启用 team4u-bean 对 Spring ApplicationContext 的自动桥接
public class FulfillmentFlowConfiguration {

    @Bean
    public Flow<OrderCommand, FulfillmentReceipt> fulfillmentFlowDefinition() {
        // 声明纯逻辑流：全部使用 Class 与 Spring Qualifier（Bean 是一等公民）
        return Flow.step(RiskCheckOperation.class)
                .then(
                        Flow.route(WarehouseSelectorOperation.class)
                                .caseOf(FulfillmentType.CLOUD, Flow.step(
                                        CloudWarehouseOperation.class, "cloudWarehouseOperation"))
                                .caseOf(FulfillmentType.LOCAL_STORE, Flow.step(
                                        LocalStoreOperation.class, "localStoreOperation"))
                                .withoutOtherwise()
                )
                // 挂载 Spring 托管的 Policy（策略键按租户投影）
                .policy(FulfillmentRatePolicy.class, command -> "tenant:" + command.getUserId())
                .named("ecommerce-fulfillment-flow");
    }

    @Bean
    public LocalExecutable<OrderCommand, FulfillmentReceipt> fulfillmentExecutable(
            Flow<OrderCommand, FulfillmentReceipt> fulfillmentFlowDefinition) {
        // 编译期一次性从 Spring 容器解析所有 Bean 依赖，并生成高性能单例执行器
        return BeanFlows.compile(fulfillmentFlowDefinition);
    }
}
```

## 3.5 业务 Service 调用

```java
package com.example.fulfillment.service;

import com.example.fulfillment.model.FulfillmentModel.*;
import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.LocalExecutable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderFulfillmentService {

    @Autowired
    private LocalExecutable<OrderCommand, FulfillmentReceipt> fulfillmentExecutable;

    public FulfillmentReceipt executeFulfillment(OrderCommand command) {
        // 极速同步执行，直接调用已绑定的 Spring Bean 单例，事务与切面完全生效
        FlowResult<FulfillmentReceipt> result = fulfillmentExecutable.run(command);
        return result.requireAccepted();
    }
}
```

## 3.6 架构优势

1. **零手工连线**：无需在 Flow 构建处通过构造函数传递各种 Service/Repository，Flow DSL 仅持有类型契约；
2. **Spring 生态全兼容**：`@Autowired`、`@Value`、`@Transactional`、AOP 日志拦截、Micrometer 监控在 `Operation` 中完全可用；
3. **极速运行期性能**：编译期完成 `BeanManager` 单例解析与缓存，运行期直接调用方法，无反射、无动态查找开销；
4. **跨环境一致**：无论在 Spring Boot Web 应用中，还是在脱离 Spring 的单元测试中，均能通过 `BeanOperationResolver` 统一解析。

---

# 4. 案例二：订单风控路由与降级

> 层级：L1 日常层 → L2 进阶层 · 前置：quick-start 4.1/4.2 · 模块：team4u-flow

**本例用到**

- API：`Flow.step / then`、`Flow.route().caseOf / withoutOtherwise`、`Flow.firstApplicable`、`Flow.scope`、`recoverWith`、`retry`、`timeout`、`named`、`FlowGraphs.mermaid`
- 痛点：通道"不适用"应是跳过而不是失败（降级链逐个尝试）；通道"技术故障"应先幂等重试、耗尽后转人工兜底；风险等级需要多通道分发
- 层：前半 L1（`route` + `firstApplicable` 的 Skipped 消费），后半 L2（`retry` / `recoverWith` / `timeout` 失败治理）

## 4.1 业务场景

电商下单核心链路：

1. **参数校验**：数量与金额不合法时正常拒绝（Rejected），返回业务提示；
2. **风控路由**：按订单风险等级路由到不同处理通道（route + opaque key 可扩展）；
3. **通道降级**：首选通道不适用（Skipped）时依次尝试备选通道（firstApplicable）；
4. **失败恢复**：通道技术故障（Failed）时落入人工兜底（recoverWith）；
5. **输出**：汇总为不可变受理凭证。

## 4.2 业务模型

```java
public class OrderRequest {
    final String orderId;
    final String userId;
    final int quantity;
    final long amount;
    final RiskLevel riskLevel;

    public OrderRequest(String orderId, String userId, int quantity,
                        long amount, RiskLevel riskLevel) {
        this.orderId = orderId;
        this.userId = userId;
        this.quantity = quantity;
        this.amount = amount;
        this.riskLevel = riskLevel;
    }
}

public enum RiskLevel { LOW, MEDIUM, HIGH }

public class Receipt {
    final String orderId;
    final String channel;
    final String outcome;

    public Receipt(String orderId, String channel, String outcome) {
        this.orderId = orderId;
        this.channel = channel;
        this.outcome = outcome;
    }

    @Override
    public String toString() {
        return "Receipt[" + orderId + "@" + channel + ": " + outcome + "]";
    }
}
```

## 4.3 Operations（全部返回四态 Outcome）

```java
// 1. 参数校验：业务拒绝走 Rejected；details 携带结构化明细
Operation<OrderRequest, OrderRequest> validate = (context, order) -> {
    if (order.quantity <= 0 || order.amount <= 0) {
        return Outcome.rejected(new Reason("INVALID_ORDER",
                "数量与金额必须为正",
                java.util.Collections.singletonMap("orderId", order.orderId)));
    }
    return Outcome.accepted(order);
};

// 2. 风控扫描：黑名单直接拒绝
Operation<OrderRequest, OrderRequest> riskScan = (context, order) -> {
    if (Blacklist.contains(order.userId)) {
        return Outcome.rejected(Reason.of("USER_BLACKLISTED", "用户命中黑名单"));
    }
    return Outcome.accepted(order);
};

// 3a. 自动通道：风控等级过高时弃权（Skipped），交给下一个分支
Operation<OrderRequest, Receipt> autoChannel = (context, order) -> {
    if (order.riskLevel == RiskLevel.HIGH) {
        return Outcome.skipped(Reason.of("RISK_TOO_HIGH",
                "高风险订单不适用自动通道"));
    }
    // 外部副作用以 invocationId 为幂等键
    String result = PaymentGateway.autoAccept(context.invocationId(), order);
    if (result == null) {
        return Outcome.failed(Failure.of("GATEWAY_ERROR", "自动通道调用失败"));
    }
    return Outcome.accepted(new Receipt(order.orderId, "AUTO", result));
};

// 3b. 半自动通道：中风险仍可处理，低风险弃权（演示 Skipped 传播）
Operation<OrderRequest, Receipt> semiAutoChannel = (context, order) -> {
    if (order.riskLevel == RiskLevel.LOW) {
        return Outcome.skipped(Reason.of("LOW_RISK_BYPASS", "低风险无需半自动"));
    }
    String result = PaymentGateway.semiAutoAccept(context.invocationId(), order);
    return Outcome.accepted(new Receipt(order.orderId, "SEMI_AUTO", result));
};

// 4. 人工兜底：失败恢复分支，输入是 Recovery<OrderRequest>
Operation<Recovery<OrderRequest>, Receipt> manualFallback = (context, recovery) -> {
    // recovery.input() 是进入失败作用域时的原始输入，recovery.failure() 是最终 Failure
    ManualQueue.enqueue(recovery.input(),
            recovery.failure().code() + ":" + recovery.failure().message());
    return Outcome.accepted(new Receipt(
            recovery.input().orderId, "MANUAL", "已转人工处理"));
};
```

## 4.4 组装 Flow

```java
public class OrderFlowFactory {

    static final Flow<OrderRequest, Receipt> ORDER_FLOW =
            Flow.<OrderRequest>step(validate)                 // INVOKE
                    .then(riskScan)                           // INVOKE
                    .then(                                                    // ROUTE
                            Flow.<OrderRequest>route(
                                            (Operation<OrderRequest, RiskLevel>)
                                                    (context, order) ->
                                                            Outcome.accepted(order.riskLevel))
                                    .caseOf(RiskLevel.LOW, Flow.step(autoChannel))
                                    .caseOf(RiskLevel.MEDIUM, degradedChannels())
                                    .caseOf(RiskLevel.HIGH, degradedChannels())
                                    .withoutOtherwise())      // 未匹配整体 Skipped
                    .named("order-risk-route");

    // 中/高风险：先试自动通道，Skipped 则半自动——首个非 Skipped 即结果
    static Flow<OrderRequest, Receipt> degradedChannels() {
        return Flow.firstApplicable(
                Flow.step(autoChannel),
                Flow.step(semiAutoChannel));
    }

    // 演示失败治理：retry 包在通道链外层（同一 invocationId 幂等重放，耗尽后 Failed 传出），
    // recoverWith 再包一层作为人工兜底边界，timeout 最外层兜住总时限。
    // 控制方法均包裹"当前 Flow"、链式自外向内生效，包裹顺序不同则语义不同
    //（见[核心语义](flow-semantics.md) 5.3 节）。
    static final Flow<OrderRequest, Receipt> ORDER_FLOW_WITH_RECOVERY =
            Flow.scope("order",
                            Flow.firstApplicable(
                                            Flow.step(autoChannel),
                                            Flow.step(semiAutoChannel))
                                    .retry(new Retry(2, java.time.Duration.ofSeconds(1))))
                    .recoverWith(Flow.step(manualFallback))
                    .timeout(java.time.Duration.ofSeconds(30));

    // 结构检查（渲染六通道图，验收时贴评审）：
    // FlowGraphs.mermaid().render(ORDER_FLOW.describe("order-risk"))
    // firstApplicable 降级边标注 "SKIPPED | next applicable"，recoverWith 恢复边标注 "FAILED | recover"
}
```

## 4.5 运行与测试

```java
public class OrderFlowTest {

    @org.junit.Test
    public void lowRiskGoesAuto() {
        // 也可用 OperationStub.accepting(x -> x) 桩替换各 Operation 后组装同构 Flow，此处演示真实 Flow
        FlowResult<Receipt> result = Local.compile(OrderFlowFactory.ORDER_FLOW)
                .run(new OrderRequest("o1", "u1", 1, 100, RiskLevel.LOW));

        org.junit.Assert.assertEquals("AUTO", result.requireAccepted().channel);
    }

    @org.junit.Test
    public void invalidOrderIsRejected() {
        FlowResult<Receipt> result = Local.compile(OrderFlowFactory.ORDER_FLOW)
                .run(new OrderRequest("o2", "u1", 0, -1, RiskLevel.LOW));

        FlowAssertions.assertRejected(result, "INVALID_ORDER");
    }

    @org.junit.Test
    public void gatewayFailureFallsBackToManual() {
        // 通道技术故障 -> recoverWith 兜底
        Flow<OrderRequest, Receipt> flow = Flow
                .<OrderRequest>step((context, order) -> Outcome.failed(
                        Failure.of("GATEWAY_ERROR", "自动通道调用失败")))
                .recoverWith(Flow.step((context, recovery) -> Outcome.accepted(
                        new Receipt(recovery.input().orderId, "MANUAL", "已转人工处理"))));

        org.junit.Assert.assertEquals("MANUAL", Local.compile(flow)
                .run(new OrderRequest("o3", "u2", 1, 99, RiskLevel.MEDIUM))
                .requireAccepted().channel);
    }
}
```

## 4.6 语义回顾

- 参数不合法 → `Rejected[INVALID_ORDER]`：正常业务分支，不触发任何恢复；
- 高风险 → 自动通道 `Skipped[RISK_TOO_HIGH]` → `firstApplicable` 消费并尝试半自动（Skipped 的三个消费位置见[核心语义](flow-semantics.md) 2.3 节）；
- 通道故障 → `Failed[GATEWAY_ERROR]` → 先 `retry`（同一 `invocationId` 幂等重放），耗尽后由 `recoverWith` 转人工；
- 渲染图中取消/挂起通道为空——ORDER_FLOW 没有 `await`，全部路径终于四个业务通道。

---

# 5. 案例三：支付审批挂起与 Durable 崩溃恢复

> 层级：L2 进阶层 → L3 引擎层 · 前置：quick-start 4.4/5 · 模块：team4u-flow + team4u-flow-durable

**本例用到**

- API：`Flow.scope / step / await(ResumePoint) / then / timeout`、`LocalExecutable.run / resume`、`DurableRuntime.compile`、`DurableExecutable.start / resume`、`StateMapper`，testkit 的 `LocalFixture / DurableFixture / FlowAssertions`
- 痛点：人工审批需要挂起等待外部信号；进程可能在任意时刻重启，执行必须可恢复
- 层：前半 L2（`await`/`resume` 在 Local 的用法），后半 L3（同一份定义换 Durable 执行器获得崩溃恢复；Durable 是独立于核心的持久化执行器组件，定位见 [Durable 文档](flow-durable.md)）

## 5.1 业务场景

支付结算需要人工审批：金额超过阈值时冻结资金并挂起等待审批结果，审批通过后完成结算。进程可能在任意时刻重启，执行必须可恢复：

1. **冻结**：调用资金服务冻结金额（幂等键 = invocationId）；
2. **挂起**：`await` 等待审批信号（本地快速路径与 Durable 共用同一定义）；
3. **恢复**：审批系统回调注入信号，`Resumed.state()` 是挂起前状态、`Resumed.signal()` 是审批结果；
4. **结算**：按审批结果结算或解冻。

## 5.2 定义（Local 与 Durable 共用）

```java
import com.fasterxml.jackson.databind.ObjectMapper;

public class PaymentApprovalSample {

    public static final class PaymentRequest {
        final String paymentId;
        final String userId;
        final long amount;

        public PaymentRequest(String paymentId, String userId, long amount) {
            this.paymentId = paymentId;
            this.userId = userId;
            this.amount = amount;
        }

        @Override
        public String toString() {
            return "Payment[" + paymentId + "/" + amount + "]";
        }
    }

    public static final class Approval {
        final boolean approved;
        final String approver;

        public Approval(boolean approved, String approver) {
            this.approved = approved;
            this.approver = approver;
        }

        @Override
        public String toString() {
            return (approved ? "APPROVED" : "DENIED") + " by " + approver;
        }
    }

    static final ResumePoint<Approval> APPROVAL = ResumePoint.named("manager-approval");

    static final Operation<PaymentRequest, PaymentRequest> freeze =
            (context, payment) -> {
                // invocationId = flowId:flowVersion:executionId:path，作为冻结幂等键
                FundsService.freeze(context.invocationId(), payment);
                return Outcome.accepted(payment);
            };

    static final Operation<Resumed<PaymentRequest, Approval>, String> settle =
            (context, resumed) -> {
                Approval approval = resumed.signal();
                if (!approval.approved) {
                    FundsService.unfreeze(resumed.state());
                    return Outcome.rejected(Reason.of("APPROVAL_DENIED",
                            "审批拒绝：" + approval.approver));
                }
                return Outcome.accepted(FundsService.settle(
                        context.invocationId(), resumed.state()));
            };

    // 一份定义，两种执行器
    static final Flow<PaymentRequest, String> PAYMENT_FLOW =
            Flow.<PaymentRequest>scope("payment",
                    Flow.<PaymentRequest>step(freeze)
                            .await(APPROVAL)      // Flow<PaymentRequest, Resumed<...>>
                            .then(settle)
                            .timeout(java.time.Duration.ofMinutes(10)));
}
```

## 5.3 Local 快速路径

```java
public class LocalApprovalDemo {
    public static void main(String[] args) {
        LocalExecutable<PaymentRequest, String> executable =
                Local.compile(PaymentApprovalSample.PAYMENT_FLOW);

        FlowResult<String> first = executable.run(
                new PaymentRequest("p1", "u1", 500_000L));

        // 挂起：FlowResult.Suspended，Suspension 单次消费
        FlowResult.Suspended<String> suspended = (FlowResult.Suspended<String>) first;

        // 审批完成后注入信号恢复
        FlowResult<String> second = executable.resume(suspended.suspension(),
                PaymentApprovalSample.APPROVAL, new Approval(true, "manager-zhang"));

        System.out.println(second.requireAccepted()); // 结算流水号
    }
}
```

## 5.4 Durable：挂起、恢复与崩溃续跑

Durable 快照需要编码业务状态（`PaymentRequest`/`Approval`），`DefaultStateMapper` 仅支持标量/`byte[]`/`Instant`，因此配置自定义 `StateMapper`（确定性契约见 [Durable 文档](flow-durable.md)；亦可用 `SerializerStateMapper`/`CompositeStateMapper` 桥接 Jackson）：

```java
import com.team4u.framework.flow.durable.DurableExecutable;
import com.team4u.framework.flow.durable.DurableResult;
import com.team4u.framework.flow.durable.DurableRuntime;
import com.team4u.framework.flow.durable.snapshot.StateMapper;
import com.team4u.framework.flow.durable.snapshot.StoredValue;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;

public class PaymentStateMapper implements StateMapper {

    private static final String CODEC = "payment-json";
    private static final int VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public StoredValue encode(Object value) throws Exception {
        return new StoredValue(CODEC, VERSION, mapper.writeValueAsBytes(value));
    }

    @Override
    public Object decode(StoredValue storedValue) throws Exception {
        return mapper.readValue(storedValue.payload(), Object.class);
    }
}
```

```java
public class DurableApprovalDemo {
    public static void main(String[] args) {
        DurableRuntime runtime = DurableRuntime.builder(new InMemoryDurableStore())
                .stateMapper(new PaymentStateMapper())
                .build();
        DurableExecutable<PaymentRequest, String> durable =
                runtime.compile(PaymentApprovalSample.PAYMENT_FLOW,
                        "payment-settle", 1);

        // 1. 启动：冻结成功后挂起，快照落 SUSPENDED + awaitingPoint
        DurableResult<String> started = durable.start(
                "pay-20240101-0001",
                new PaymentRequest("p1", "u1", 500_000L));
        System.out.println(started.getClass().getSimpleName()); // Suspended

        // 2. 注入审批信号：两段 CAS（信号先落库为 resume:manager-approval 槽）
        DurableResult<String> resumed = durable.resume(
                "pay-20240101-0001", "manager-approval",
                new Approval(true, "manager-zhang"));
        System.out.println(resumed.requireAccepted());

        // 3. 崩溃恢复：进程重启后重新 compile（同一 store、同一 StateMapper），
        //    从最后提交快照续跑（快照中仅元数据 + 编码槽，业务对象经 mapper 重建）
        DurableExecutable<PaymentRequest, String> rebuilt =
                DurableRuntime.builder(new InMemoryDurableStore()) // 生产中为 JDBC 实现
                        .stateMapper(new PaymentStateMapper())
                        .build()
                        .compile(PaymentApprovalSample.PAYMENT_FLOW,
                                "payment-settle", 1);
        // 挂起中的执行：直接 rebuilt.resume(id, "manager-approval", signal) 正常续接
        //   （recover 仅用于 ACTIVE 执行，如 retry 退避唤醒）
        // 信号已落库但未消费时崩溃：
        //   同值信号 resume 幂等重驱动；异值信号报 RESUME_SIGNAL_CONFLICT
    }
}
```

## 5.5 测试（testkit 全程护航）

```java
public class PaymentApprovalTest {

    private final LocalFixture<PaymentRequest, String> fixture =
            LocalFixture.compile(PaymentApprovalSample.PAYMENT_FLOW);

    @org.junit.Test
    public void suspendsAtApprovalThenSettles() {
        // 挂起后注入审批通过信号
        Suspension<String> suspension = fixture.requireSuspension(
                new PaymentRequest("p1", "u1", 500_000L));
        org.junit.Assert.assertEquals("manager-approval", suspension.resumePoint());

        FlowResult<String> result = fixture.resume(suspension,
                PaymentApprovalSample.APPROVAL, new Approval(true, "manager-zhang"));
        FlowAssertions.assertCompleted(result);
    }

    @org.junit.Test
    public void denialIsBusinessReject() {
        Suspension<String> suspension = fixture.requireSuspension(
                new PaymentRequest("p2", "u2", 999_999L));
        FlowResult<String> result = fixture.resume(suspension,
                PaymentApprovalSample.APPROVAL, new Approval(false, "manager-li"));
        FlowAssertions.assertRejected(result, "APPROVAL_DENIED");
    }

    @org.junit.Test
    public void durableSurvivesRestart() {
        DurableStore store = new InMemoryDurableStore();
        DurableRuntime runtime = DurableRuntime.builder(store)
                .stateMapper(new PaymentStateMapper())
                .build();
        DurableFixture<PaymentRequest, String> durable =
                DurableFixture.withRuntime(
                        runtime, PaymentApprovalSample.PAYMENT_FLOW, "payment-settle", 1);

        DurableResult<String> started =
                durable.start("pay-t1", new PaymentRequest("p3", "u3", 1_000L));
        FlowAssertions.assertSuspended(started, "manager-approval");

        // 模拟重启：同一 store + 同一 StateMapper 重新 compile（跨进程等价）；
        // 挂起中的执行直接 resume 即可续接（recover 仅用于 ACTIVE 执行）
        DurableFixture<PaymentRequest, String> afterRestart =
                DurableFixture.withRuntime(
                        runtime, PaymentApprovalSample.PAYMENT_FLOW, "payment-settle", 1);

        // 注入审批信号完成结算（两段 CAS：信号先落库再驱动）
        DurableResult<String> done =
                afterRestart.resume("pay-t1", PaymentApprovalSample.APPROVAL,
                        new Approval(true, "manager-zhang"));
        FlowAssertions.assertCompleted(done);
    }
}
```

## 5.6 语义回顾

- `await` 让同一份定义在 Local（内存 `Suspension`）与 Durable（快照挂起）下语义一致；
- `Resumed.state()` 保留挂起前的 scope entry，`Resumed.signal()` 携带类型化审批结果；
- Durable resume 两段 CAS 保证"信号先落库"，崩溃在任何点都能收敛：同值幂等、异值冲突、挂起可重发；
- `(flowId="payment-settle", flowVersion=1)` 是快照归属边界；流程结构变更必须递增版本号，旧快照不做迁移。

---

# 6. 延伸阅读

[核心语义与机制](flow-semantics.md)（四态传播、Skipped 消费位置、控制机制与诊断码全集）、[Bean 容器集成](flow-bean.md)、[Durable 持久化执行](flow-durable.md)、[可视化与图表渲染](flow-graph.md)、[测试支持与断言](flow-test.md)、[扩展机制与 SPI](flow-extension.md)。
