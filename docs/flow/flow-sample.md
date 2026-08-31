# 实战案例

本章给出两个完整可运行风格的示例：订单风控路由 + firstApplicable 降级，支付审批 await/resume + Durable 恢复。

---

# 案例一：订单风控路由与降级

## 业务场景

电商下单核心链路：

1. **参数校验**：数量与金额不合法时正常拒绝（Rejected），返回业务提示；
2. **风控路由**：按订单风险等级路由到不同处理通道（route + opaque key 可扩展）；
3. **通道降级**：首选通道不适用（Skipped）时依次尝试备选通道（firstApplicable）；
4. **失败恢复**：通道技术故障（Failed）时落入人工兜底（recoverWith）；
5. **输出**：汇总为不可变受理凭证。

## 业务模型

```java
import com.team4u.framework.flow.*;

public class OrderRiskSample {

    // ---------- 业务模型 ----------

    public static final class OrderRequest {
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

        String toInvocationKey() {
            return "order:" + orderId;
        }
    }

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    public static final class Receipt {
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
}
```

## Operations（全部返回四态 Outcome）

```java
// 1. 参数校验：业务拒绝走 Rejected
Operation<OrderRequest, OrderRequest> validate = (context, order) -> {
    if (order.quantity <= 0 || order.amount <= 0) {
        return Outcome.rejected(Reason.of("INVALID_ORDER",
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
    // recovery.input() 是原始 scope 输入，recovery.failure() 是最终 Failure
    ManualQueue.enqueue(recovery.input(),
            recovery.failure().code() + ":" + recovery.failure().message());
    return Outcome.accepted(new Receipt(
            recovery.input().orderId, "MANUAL", "已转人工处理"));
};
```

## 组装 Flow

```java
import com.team4u.framework.flow.test.*;

public class OrderFlowFactory {

    static final Flow<OrderRequest, Receipt> ORDER_FLOW =
            Flow.<OrderRequest>step(validate)                 // INVOKE
                    .then(riskScan)                           // INVOKE
                    .then(                                                    // ROUTE
                            Flow.<OrderRequest>route(
                                            (Operation<OrderRequest, RiskLevel>)
                                                    (context, order) ->
                                                            Outcome.accepted(order.riskLevel))
                                    .caseOf(RiskLevel.LOW, autoChannelAsFlow())
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

    static Flow<OrderRequest, Receipt> autoChannelAsFlow() {
        return Flow.step(autoChannel);
    }

    // 演示 recoverWith：包一层 scope 作为失败恢复边界
    static final Flow<OrderRequest, Receipt> ORDER_FLOW_WITH_RECOVERY =
            Flow.scope("order", Flow.firstApplicable(
                            Flow.step(autoChannel),
                            Flow.step(semiAutoChannel)))
                    .recoverWith(Flow.step(manualFallback))
                    .retry(new Retry(2, java.time.Duration.ofSeconds(1)))
                    .timeout(java.time.Duration.ofSeconds(30));
}
```

结构检查（渲染六通道图，验收时贴评审）：

```java
String graph = com.team4u.framework.flow.graph.FlowGraphs.mermaid()
        .render(ORDER_FLOW.describe("order-risk"));
// firstApplicable 节点呈现 "SKIPPED | next applicable"，recoverWith 呈现 "FAILED | recover"
```

## 运行与测试

```java
public class OrderFlowTest {

    @org.junit.Test
    public void lowRiskGoesAuto() {
        OperationStub<OrderRequest, OrderRequest> validateStub =
                OperationStub.accepting(x -> x);
        // ... 以桩替换各 Operation 后组装同构 Flow，此处以真实 Flow 演示
        FlowResult<Receipt> result = Local.compile(OrderFlowFactory.ORDER_FLOW)
                .run(new OrderRequest("o1", "u1", 1, 100, RiskLevel.LOW));

        FlowAssertions.assertAccepted(result, new Receipt("o1", "AUTO", "ok"));
    }

    @org.junit.Test
    public void invalidOrderIsRejected() {
        FlowResult<Receipt> result = Local.compile(
                        Flow.<OrderRequest>step((context, order) ->
                                Outcome.rejected(Reason.of("INVALID_ORDER", "非法订单"))))
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

        FlowAssertions.assertAccepted(
                Local.compile(flow).run(new OrderRequest("o3", "u2", 1, 99, RiskLevel.MEDIUM)),
                new Receipt("o3", "MANUAL", "已转人工处理"));
    }
}
```

语义回顾：

- 参数不合法 → `Rejected[INVALID_ORDER]`：正常业务分支，不触发任何恢复；
- 高风险 → 自动通道 `Skipped` → `firstApplicable` 消费并尝试半自动；
- 通道故障 → `Failed[GATEWAY_ERROR]` → 先 `retry`（同 invocationId 幂等重放），耗尽后 `recoverWith` 转人工；
- 渲染图中取消/挂起通道为空——本流程没有 await，全部路径终于四个业务通道。

---

# 案例二：支付审批 await/resume + Durable 恢复

## 业务场景

支付结算需要人工审批：金额超过阈值时冻结资金并挂起等待审批结果，审批通过后完成结算。进程可能在任意时刻重启，执行必须可恢复：

1. **冻结**：调用资金服务冻结金额（幂等键 = invocationId）；
2. **挂起**：`await` 等待审批信号（本地快速路径与 Durable 共用同一定义）；
3. **恢复**：审批系统回调注入信号，`Resumed.state()` 是挂起前状态、`Resumed.signal()` 是审批结果；
4. **结算**：按审批结果结算或解冻。

## 定义（Local 与 Durable 共用）

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.flow.*;
import com.team4u.framework.flow.durable.*;

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

    static final ResumePoint<Approval> APPROVAL =
            ResumePoint.named("manager-approval");

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
                return Outcome.accepted(
                        FundsService.settle(context.invocationId(), resumed.state()));
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

## Local 快速路径

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
        FlowResult<String> second = executable.resume(
                suspended.suspension(),
                PaymentApprovalSample.APPROVAL,
                new Approval(true, "manager-zhang"));

        System.out.println(second.requireAccepted()); // 结算流水号
    }
}
```

## Durable：挂起、恢复与崩溃续跑

Durable 快照需要编码业务状态（`PaymentRequest`/`Approval`），`DefaultStateMapper` 仅支持标量/`byte[]`/`Instant`，因此配置自定义 `StateMapper`（确定性契约见 [Durable 文档](flow-durable.md)）：

```java
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

        // 3. 崩溃恢复：进程重启后重新 compile（配置同一 StateMapper），
        //    从最后提交快照续跑（快照中仅元数据 + 编码槽，业务对象经 mapper 重建）
        DurableExecutable<PaymentRequest, String> rebuilt =
                DurableRuntime.builder(reloadStore())
                        .stateMapper(new PaymentStateMapper())
                        .build()
                        .compile(PaymentApprovalSample.PAYMENT_FLOW,
                                "payment-settle", 1);
        // 挂起中的执行：直接 rebuilt.resume(id, "manager-approval", signal) 正常续接
        //   （recover 仅用于 ACTIVE 执行，如 retry 退避唤醒）
        // 信号已落库但未消费时崩溃：
        //   同值信号 resume 幂等重驱动；异值信号报 RESUME_SIGNAL_CONFLICT
    }

    static com.team4u.framework.flow.durable.DurableStore reloadStore() {
        // 生产中为 JDBC 实现；演示用内存 store
        return new com.team4u.framework.flow.durable.InMemoryDurableStore();
    }
}
```

## 测试（testkit 全程护航）

```java
public class PaymentApprovalTest {

    private final LocalFixture<PaymentRequest, String> fixture =
            LocalFixture.compile(PaymentApprovalSample.PAYMENT_FLOW);

    @org.junit.Test
    public void suspendsAtApprovalThenSettles() {
        // 挂起
        Suspension<String> suspension = fixture.requireSuspension(
                new PaymentRequest("p1", "u1", 500_000L));
        org.junit.Assert.assertEquals("manager-approval", suspension.resumePoint());

        // 恢复：审批通过
        FlowResult<String> result = fixture.resume(
                suspension, PaymentApprovalSample.APPROVAL,
                new Approval(true, "manager-zhang"));
        FlowAssertions.assertCompleted(result);
    }

    @org.junit.Test
    public void denialIsBusinessReject() {
        Suspension<String> suspension = fixture.requireSuspension(
                new PaymentRequest("p2", "u2", 999_999L));
        FlowResult<String> result = fixture.resume(
                suspension, PaymentApprovalSample.APPROVAL,
                new Approval(false, "manager-li"));
        FlowAssertions.assertRejected(result, "APPROVAL_DENIED");
    }

    @org.junit.Test
    public void durableSurvivesRestart() {
        com.team4u.framework.flow.durable.DurableStore store =
                new com.team4u.framework.flow.durable.InMemoryDurableStore();
        com.team4u.framework.flow.durable.DurableRuntime runtime =
                com.team4u.framework.flow.durable.DurableRuntime.builder(store)
                        .stateMapper(new PaymentStateMapper())
                        .build();
        com.team4u.framework.flow.test.DurableFixture<PaymentRequest, String> durable =
                com.team4u.framework.flow.test.DurableFixture.withRuntime(
                        runtime, PaymentApprovalSample.PAYMENT_FLOW, "payment-settle", 1);

        com.team4u.framework.flow.durable.DurableResult<String> started =
                durable.start("pay-t1", new PaymentRequest("p3", "u3", 1_000L));
        FlowAssertions.assertSuspended(started, "manager-approval");

        // 模拟重启：同一 store + 同一 StateMapper 重新 compile（跨进程等价）；
        // 挂起中的执行直接 resume 即可续接（recover 仅用于 ACTIVE 执行）
        com.team4u.framework.flow.test.DurableFixture<PaymentRequest, String> afterRestart =
                com.team4u.framework.flow.test.DurableFixture.withRuntime(
                        runtime, PaymentApprovalSample.PAYMENT_FLOW, "payment-settle", 1);

        // 注入审批信号完成结算（两段 CAS：信号先落库再驱动）
        com.team4u.framework.flow.durable.DurableResult<String> done =
                afterRestart.resume("pay-t1", PaymentApprovalSample.APPROVAL,
                        new Approval(true, "manager-zhang"));
        FlowAssertions.assertCompleted(done);
    }
}
```

语义回顾：

- `await` 让同一份定义在 Local（内存 Suspension）与 Durable（快照挂起）下语义一致；
- `Resumed.state()` 保留挂起前的 scope entry，`Resumed.signal()` 携带类型化审批结果；
- Durable resume 两段 CAS 保证"信号先落库"，崩溃在任何点都能收敛：同值幂等、异值冲突、挂起可重发；
- `(flowId="payment-settle", flowVersion=1)` 是快照归属边界；流程结构变更必须递增版本号，旧快照不做迁移。
