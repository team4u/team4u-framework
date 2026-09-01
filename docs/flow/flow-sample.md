# 实战案例

本章提供三个端到端业务实战案例，涵盖 Spring 容器集成、复杂风控路由与容错降级、人工审批挂起与跨进程崩溃恢复。

| 案例 | 核心演示特性 | 适用场景 |
| :--- | :--- | :--- |
| **电商履约** | Class / Qualifier 声明式编排、Spring 事务与 AOP 代理保留、多渠道路由 | 企业级 Spring Boot 业务流程 |
| **订单风控** | `route` 多通道分发、`firstApplicable` 降级链、`retry` / `recoverWith` 失败治理 | 高可用交易链路、多级回退 |
| **支付结算** | `await` / `resume` 挂起恢复、Durable 检查点持久化、`StateMapper` 编解码与崩溃续跑 | 人工审批流、跨进程长事务 |

---

## 公共模型与辅助定义

以下为各案例共用的基础定义与模拟依赖：

```java
import com.team4u.framework.flow.*;
import com.team4u.framework.flow.api.*;
import com.team4u.framework.flow.model.*;
import com.team4u.framework.flow.test.*;

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

## 电商履约：Spring Bean 容器集成实战

### 业务场景

在企业级 Spring Boot 应用中，业务步骤通常依赖 Spring 托管的组件（如 DAO、RPC Client 等），并要求支持 Spring 声明式事务（`@Transactional`）与 AOP 切面。

本案例演示：
- 所有业务步骤（`Operation`）与准入策略（`Policy`）均声明为 Spring `@Component`；
- 流程 DSL 仅引用 Class 与 Qualifier，编译期一次性解析绑定，运行期零反射；
- 多渠道仓储策略路由（云仓 `cloudWarehouseOperation` vs 门店前置仓 `localStoreOperation`）；
- `@Transactional` 事务切面在执行期透明生效。

### 业务模型

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

### 声明 Spring Bean 业务操作与策略

```java
package com.example.fulfillment.operation;

import com.example.fulfillment.model.FulfillmentModel.*;
import com.team4u.framework.flow.api.*;
import com.team4u.framework.flow.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 1. 租户限流 Policy（Spring 托管单例）
@Component
public class FulfillmentRatePolicy implements Policy<String> {

    @Autowired
    private RedisRateLimiter rateLimiter; // 应用内 Spring Bean

    @Override
    public Gate before(PolicyContext context, String tenantKey) {
        if (!rateLimiter.tryAcquire(tenantKey, 1)) {
            return Gate.reject(Reason.of("RATE_LIMITED", "租户履约请求超限"));
        }
        return Gate.proceed();
    }
}

// 2. 风控校验 Operation
@Component
public class RiskCheckOperation implements Operation<OrderCommand, OrderCommand> {

    @Autowired
    private RiskRpcClient riskRpcClient;

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

// 3. 履约渠道路由器
@Component
public class WarehouseSelectorOperation implements Operation<OrderCommand, FulfillmentType> {
    @Override
    public Outcome<FulfillmentType> execute(OperationContext context, OrderCommand command) {
        return Outcome.accepted(command.getType());
    }
}

// 4a. 云仓履约通道（支持声明式事务）
@Component("cloudWarehouseOperation")
public class CloudWarehouseOperation implements Operation<OrderCommand, FulfillmentReceipt> {

    @Autowired
    private InventoryService inventoryService;

    @Override
    @Transactional(rollbackFor = Exception.class) // Spring 事务切面生效
    public Outcome<FulfillmentReceipt> execute(OperationContext context, OrderCommand command) {
        String tracking = inventoryService.lockAndDispatchCloud(
                context.invocationId(), command.getOrderId(), command.getSku(), command.getQuantity());
        return Outcome.accepted(new FulfillmentReceipt(command.getOrderId(), tracking, "WH-CLOUD-01"));
    }
}

// 4b. 门店前置仓履约通道
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

### 容器配置与 Flow 编译

```java
package com.example.fulfillment.config;

import com.example.fulfillment.model.FulfillmentModel.*;
import com.example.fulfillment.operation.*;
import com.team4u.framework.bean.spring.Team4uBeanConfiguration;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(Team4uBeanConfiguration.class) // 桥接 Spring 容器至 BeanManager
public class FulfillmentFlowConfiguration {

    @Bean
    public Flow<OrderCommand, FulfillmentReceipt> fulfillmentFlowDefinition() {
        // 声明纯逻辑流：全部使用 Class 与 Spring Qualifier
        return Flow.step(RiskCheckOperation.class)
                .then(
                        Flow.route(WarehouseSelectorOperation.class)
                                .caseOf(FulfillmentType.CLOUD, Flow.step(
                                        CloudWarehouseOperation.class, "cloudWarehouseOperation"))
                                .caseOf(FulfillmentType.LOCAL_STORE, Flow.step(
                                        LocalStoreOperation.class, "localStoreOperation"))
                                .withoutOtherwise()
                )
                .policy(FulfillmentRatePolicy.class, command -> "tenant:" + command.getUserId())
                .named("ecommerce-fulfillment-flow");
    }

    @Bean
    public LocalExecutable<OrderCommand, FulfillmentReceipt> fulfillmentExecutable(
            Flow<OrderCommand, FulfillmentReceipt> fulfillmentFlowDefinition) {
        // 引入 team4u-flow-bean 后，Local.compile 默认自动从 Spring 容器解析所有 Bean 依赖
        return Local.compile(fulfillmentFlowDefinition);
    }
}
```

### 业务 Service 调用

```java
@Service
public class OrderFulfillmentService {

    @Autowired
    private LocalExecutable<OrderCommand, FulfillmentReceipt> fulfillmentExecutable;

    public FulfillmentReceipt executeFulfillment(OrderCommand command) {
        FlowResult<FulfillmentReceipt> result = fulfillmentExecutable.run(command);
        return result.requireAccepted();
    }
}
```

### 设计优势

- **类型安全**：DSL 仅持有类型契约，无硬编码字符串反射调用；
- **Spring 生态全兼容**：`@Autowired`、`@Transactional`、AOP 日志拦截在 `Operation` 中完全可用；
- **运行期零损耗**：编译期完成单例解析与缓存，运行期直接调用方法；
- **跨环境一致**：在脱离 Spring 的单元测试中亦可通过 `BeanOperationResolver` 统一解析测试桩。

---

## 订单风控：路由分发与容错降级实战

### 业务场景

下单业务核心链路包含参数校验、风控扫描、多级通道降级与失败补偿：
- **参数校验**：数量与金额不合法时返回业务拒绝（`Rejected`）；
- **风控路由**：按订单风险等级路由到不同处理通道；
- **通道降级**：首选通道不适用（`Skipped`）时依次尝试备选通道（`firstApplicable`）；
- **失败恢复**：通道技术故障（`Failed`）时落入人工兜底（`recoverWith`）。

### 业务模型

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

### 业务操作实现

```java
// 1. 参数校验：业务拒绝走 Rejected
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

// 3. 自动通道：高风险时弃权（Skipped）
Operation<OrderRequest, Receipt> autoChannel = (context, order) -> {
    if (order.riskLevel == RiskLevel.HIGH) {
        return Outcome.skipped(Reason.of("RISK_TOO_HIGH", "高风险订单不适用自动通道"));
    }
    String result = PaymentGateway.autoAccept(context.invocationId(), order);
    if (result == null) {
        return Outcome.failed(Failure.of("GATEWAY_ERROR", "自动通道调用失败"));
    }
    return Outcome.accepted(new Receipt(order.orderId, "AUTO", result));
};

// 4. 半自动通道：低风险时弃权
Operation<OrderRequest, Receipt> semiAutoChannel = (context, order) -> {
    if (order.riskLevel == RiskLevel.LOW) {
        return Outcome.skipped(Reason.of("LOW_RISK_BYPASS", "低风险无需半自动"));
    }
    String result = PaymentGateway.semiAutoAccept(context.invocationId(), order);
    return Outcome.accepted(new Receipt(order.orderId, "SEMI_AUTO", result));
};

// 5. 人工兜底：失败恢复分支，入参为 Recovery<OrderRequest>
Operation<Recovery<OrderRequest>, Receipt> manualFallback = (context, recovery) -> {
    ManualQueue.enqueue(recovery.input(),
            recovery.failure().code() + ":" + recovery.failure().message());
    return Outcome.accepted(new Receipt(
            recovery.input().orderId, "MANUAL", "已转人工处理"));
};
```

### Flow 编排与控制挂载

```java
public class OrderFlowFactory {

    static final Flow<OrderRequest, Receipt> ORDER_FLOW =
            Flow.<OrderRequest>step(validate)
                    .then(riskScan)
                    .then(
                            Flow.<OrderRequest>route(
                                            (Operation<OrderRequest, RiskLevel>)
                                                    (context, order) ->
                                                            Outcome.accepted(order.riskLevel))
                                    .caseOf(RiskLevel.LOW, Flow.step(autoChannel))
                                    .caseOf(RiskLevel.MEDIUM, degradedChannels())
                                    .caseOf(RiskLevel.HIGH, degradedChannels())
                                    .withoutOtherwise())
                    .named("order-risk-route");

    static Flow<OrderRequest, Receipt> degradedChannels() {
        return Flow.firstApplicable(
                Flow.step(autoChannel),
                Flow.step(semiAutoChannel));
    }

    // 附带重试、失败补偿与超时的完整流程
    static final Flow<OrderRequest, Receipt> ORDER_FLOW_WITH_RECOVERY =
            Flow.scope("order",
                            Flow.firstApplicable(
                                            Flow.step(autoChannel),
                                            Flow.step(semiAutoChannel))
                    .persistentPolicy(
                            FlowRetryPolicy.fixed(2, 1000), OrderRequest::getOrderId))
                    .recoverWith(Flow.step(manualFallback))
                    .timeout(java.time.Duration.ofSeconds(30));
}
```

### 运行与断言测试

```java
public class OrderFlowTest {

    @org.junit.Test
    public void lowRiskGoesAuto() {
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
        Flow<OrderRequest, Receipt> flow = Flow
                .<OrderRequest>step((context, order) -> Outcome.failed(
                        Failure.of("GATEWAY_ERROR", "自动通道调用失败")))
                .recoverWith(Flow.step((context, recovery) -> Outcome.accepted(
                        new Receipt(recovery.input().orderId, "MANUAL", "已转人工处理"))));

        FlowResult<Receipt> result = Local.compile(flow)
                .run(new OrderRequest("o3", "u2", 1, 99, RiskLevel.MEDIUM));

        org.junit.Assert.assertEquals("MANUAL", result.requireAccepted().channel);
    }
}
```

### 语义流转分析

- **参数不合法**：直接输出 `Rejected[INVALID_ORDER]`，正常业务短路，不触发重试与降级；
- **高风险订单**：自动通道返回 `Skipped[RISK_TOO_HIGH]`，由 `firstApplicable` 消费并尝试半自动通道；
- **通道故障**：返回 `Failed[GATEWAY_ERROR]`，先触发 `retry`（保持稳定 `invocationId`），重试耗尽后由 `recoverWith` 转入人工兜底。

---

## 支付结算：审批挂起与 Durable 崩溃恢复实战

### 业务场景

支付结算场景中，大额支付需要挂起等待人工审批：
- **冻结资金**：调用资金服务冻结账户金额（以 `invocationId` 为幂等键）；
- **挂起等待**：`await` 挂起流程等待审批信号；
- **恢复与结算**：审批系统回调注入信号，按审批结论结算或解冻；
- **持久化保证**：进程可在任意时刻重启，Durable 执行器支持从最后快照断点续跑。

### 业务模型与流程定义

```java
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
    }

    public static final class Approval {
        final boolean approved;
        final String approver;

        public Approval(boolean approved, String approver) {
            this.approved = approved;
            this.approver = approver;
        }
    }

    static final ResumePoint<Approval> APPROVAL = ResumePoint.named("manager-approval");

    static final Operation<PaymentRequest, PaymentRequest> freeze =
            (context, payment) -> {
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

    // 同一份 Flow 定义，适配 Local 与 Durable 执行器
    static final Flow<PaymentRequest, String> PAYMENT_FLOW =
            Flow.<PaymentRequest>scope("payment",
                    Flow.<PaymentRequest>step(freeze)
                            .await(APPROVAL)
                            .then(settle)
                            .timeout(java.time.Duration.ofMinutes(10)));
}
```

### Local 同步执行与挂起恢复

```java
public class LocalApprovalDemo {
    public static void main(String[] args) {
        LocalExecutable<PaymentRequest, String> executable =
                Local.compile(PaymentApprovalSample.PAYMENT_FLOW);

        FlowResult<String> first = executable.run(
                new PaymentRequest("p1", "u1", 500_000L));

        // 挂起态返回 FlowResult.Suspended
        FlowResult.Suspended<String> suspended = (FlowResult.Suspended<String>) first;

        // 审批通过后注入恢复信号
        FlowResult<String> second = executable.resume(suspended.suspension(),
                PaymentApprovalSample.APPROVAL, new Approval(true, "manager-zhang"));

        System.out.println(second.requireAccepted()); // 结算流水号
    }
}
```

### Durable 持久化与崩溃续跑

针对自定义业务对象配置 `StateMapper`：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.flow.durable.snapshot.StateMapper;
import com.team4u.framework.flow.durable.snapshot.StoredValue;

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

Durable 挂起、两段 CAS 恢复与断点续跑：

```java
import com.team4u.framework.flow.durable.*;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;

public class DurableApprovalDemo {
    public static void main(String[] args) {
        DurableRuntime runtime = DurableRuntime.builder(new InMemoryDurableStore())
                .stateMapper(new PaymentStateMapper())
                .build();

        DurableExecutable<PaymentRequest, String> durable =
                runtime.compile(PaymentApprovalSample.PAYMENT_FLOW, "payment-settle", 1);

        // 1. 启动执行：资金冻结后挂起，落 SUSPENDED 检查点
        DurableResult<String> started = durable.start(
                "pay-20240101-0001",
                new PaymentRequest("p1", "u1", 500_000L));

        // 2. 注入审批信号：两段 CAS（信号先落库再驱动）
        DurableResult<String> resumed = durable.resume(
                "pay-20240101-0001", "manager-approval",
                new Approval(true, "manager-zhang"));
        System.out.println(resumed.requireAccepted());

        // 3. 模拟进程重启：重新 compile 后直接续跑
        DurableExecutable<PaymentRequest, String> rebuilt =
                runtime.compile(PaymentApprovalSample.PAYMENT_FLOW, "payment-settle", 1);
        DurableResult<String> recovered = rebuilt.recover("pay-20240101-0001");
    }
}
```

### 单元测试与断言

```java
public class PaymentApprovalTest {

    private final LocalFixture<PaymentRequest, String> fixture =
            LocalFixture.compile(PaymentApprovalSample.PAYMENT_FLOW);

    @org.junit.Test
    public void suspendsAtApprovalThenSettles() {
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

        // 模拟进程重启后注入审批信号完成结算
        DurableFixture<PaymentRequest, String> afterRestart =
                DurableFixture.withRuntime(
                        runtime, PaymentApprovalSample.PAYMENT_FLOW, "payment-settle", 1);

        DurableResult<String> done =
                afterRestart.resume("pay-t1", PaymentApprovalSample.APPROVAL,
                        new Approval(true, "manager-zhang"));
        FlowAssertions.assertCompleted(done);
    }
}
```

### 语义机制分析

- `await` 在 Local（内存 `Suspension`）与 Durable（快照挂起）下保持一致的挂起契约；
- `Resumed.state()` 保存挂起前的原值，`Resumed.signal()` 携带强类型审批结果；
- Durable resume 采用两段 CAS 提交，崩溃在任何阶段均具备自愈与幂等保证。

---

## 延伸阅读

- [核心语义与机制](flow-semantics.md)：四态流转与传播规则
- [Bean 容器集成](flow-bean.md)：Spring 容器绑定与切面代理
- [Durable 持久化执行](flow-durable.md)：CAS 检查点与崩溃恢复机制
- [测试支持与断言](flow-test.md)：全套测试桩与断言工具
