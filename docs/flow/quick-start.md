# 快速开始

> 层级：L1 → L2 → L3 递进 · 前置：无 · 模块：team4u-flow（按站递增）

本文用三站递进走完最短上手路径：第 1 站用 8 个概念覆盖约九成日常场景；第 2 站解锁并行、挂起与治理控制；第 3 站换一个执行器获得崩溃恢复能力。每一站都可以是终点——停在第一站不是逃课：只用这一层，它就是一个类型安全的 pipeline 库，没有并发模型、没有持久化协议的心智负担。

尾部附两个全层通用工具小节：图渲染（graph）与测试（testkit）。

---

# 1. 第 1 站（L1 日常层）：类型化流水线

## 1.1 心智模型：定义 → Local 执行

```text
┌────────────────────────────────────┐
│ 声明期：Flow<I, O>                  │ 纯不可变 AST，只描述结构，本身不可执行
└─────────────────┬──────────────────┘
                  │ Local.compile(flow)（静态校验 + 解析绑定）
                  ▼
┌────────────────────────────────────┐
│ 执行期：LocalExecutable<I, O>       │ 线程安全单例，可重复调用
└─────────────────┬──────────────────┘
                  │ run(input)
                  ▼
     FlowResult：Completed(outcome) / Suspended / Cancelled（第 2 站展开后两态）
     Outcome：Accepted / Rejected / Skipped / Failed（仅 Accepted 携值，见 1.4）
```

第 1 站只需记住两条：

- **不可变定义**：所有组合方法（`then`、`route` 等）均返回新的 `Flow` 实例，原实例不变，天然线程安全。
- **四态业务结果**：业务步骤不抛业务异常，而是返回四态 `Outcome`，见 1.4。

## 1.2 引入依赖：先最小集，再按需递增

第一档——本站最小集，只要核心模块：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.team4u</groupId>
            <artifactId>team4u-framework</artifactId>
            <version>1.0.0-SNAPSHOT</version> <!-- 对齐仓库发布版本 -->
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- 核心：类型化 Flow DSL + Local 执行器，零第三方运行时依赖 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-flow</artifactId>
    </dependency>
</dependencies>
```

第二档——可选模块，走到哪站再引入哪个：

| 模块 | 何时引入 |
| :--- | :--- |
| `team4u-flow-bean`（配合 `team4u-bean-spring`） | Spring/Bean 容器绑定编排（见 1.7） |
| `team4u-flow-durable` | 第 3 站：崩溃恢复 |
| `team4u-flow-graph` | 渲染流程结构图（见第 4 节） |
| `team4u-flow-test`（scope=test） | 测试桩与断言（见第 5 节） |

## 1.3 纯 Java 流水线：step / then

业务步骤实现 `Operation<I, O>`，返回四态 `Outcome`；用 `Flow.step(...).then(...)` 组合成类型化流水线：

```java
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;

public class QuickStart {

    static Operation<String, Integer> length = (context, value) ->
            Outcome.accepted(value.length());

    static Operation<Integer, String> label = (context, value) ->
            Outcome.accepted("len=" + value);

    public static void main(String[] args) {
        // 1. Flow<I, O> 只描述结构，本身不可执行
        Flow<String, String> flow = Flow.step(length).then(label);

        // 2. 编译为 Local 可执行句柄（静态校验拓扑、解析绑定、优化执行帧栈）
        LocalExecutable<String, String> executable = Local.compile(flow);

        // 3. 同步执行
        FlowResult<String> result = executable.run("team4u");
        System.out.println(result.requireAccepted()); // len=6
    }
}
```

要点：

- `then` 前后类型严格推导：`Flow<String, Integer>.then(Operation<Integer, String>)` 得到 `Flow<String, String>`，不匹配直接编译错误。
- **为什么不能直接 `flow.run()`**：`Flow` 是纯逻辑拓扑（不可变 AST）。`Local.compile` 阶段完成结构校验与绑定解析；编译产物 `LocalExecutable` 是线程安全的高性能单例，生产中应以容器单例托管并重复调用。

## 1.4 四态初见：Outcome

`Outcome<T>` 是业务结果的四态闭集，仅 `Accepted` 携带输出：

```java
Operation<OrderRequest, Receipt> checkout = (context, order) -> {
    if (order.getAmount() <= 0) {
        return Outcome.rejected(Reason.of("INVALID_AMOUNT", "金额必须为正"));
    }
    if (!inventoryClient.tryReserve(order.getInvocationKey())) {
        return Outcome.skipped(Reason.of("OUT_OF_STOCK", "库存不足，暂不处理"));
    }
    Receipt receipt = paymentClient.charge(order);
    if (receipt == null) {
        return Outcome.failed(Failure.of("PAYMENT_ERROR", "支付渠道异常"));
    }
    return Outcome.accepted(receipt);
};
```

按状态消费结果：

```java
FlowResult<Receipt> result = Local.compile(Flow.step(checkout)).run(order);
if (result instanceof FlowResult.Completed) {
    Outcome<Receipt> outcome = ((FlowResult.Completed<Receipt>) result).outcome();
    if (outcome instanceof Outcome.Accepted) {
        handleSuccess(((Outcome.Accepted<Receipt>) outcome).value());
    } else if (outcome instanceof Outcome.Rejected) {
        handleReject(((Outcome.Rejected<Receipt>) outcome).reason());
    } else if (outcome instanceof Outcome.Skipped) {
        handleSkip(((Outcome.Skipped<Receipt>) outcome).reason());
    } else {
        handleFailure(((Outcome.Failed<Receipt>) outcome).failure());
    }
}
```

传播与异常要点：

- 普通 `then` 仅 Accepted 推进；Rejected / Skipped / Failed 短路向外传播，直到命中对应控制节点（如 `firstApplicable`、`retry`）或作为最终结果输出。
- 业务代码抛出的未捕获异常（NPE / RPC 异常等）不会逃逸引擎，一律转换为携带稳定码 `OPERATION_EXCEPTION` 的 `Failed`，可被 `retry` / `recoverWith` 消费（第 2 站）。
- 测试时用 testkit 的 `FlowAssertions.assertAccepted / assertRejected / ...` 一行断言（见第 5 节）。

## 1.5 route：条件路由

```java
Flow<OrderRequest, Receipt> routed = Flow
        .route((Operation<OrderRequest, String>) (context, order) ->
                Outcome.accepted(order.getChannel()))
        .caseOf("ALIPAY", alipayFlow)
        .caseOf("WECHAT", wechatFlow)
        .otherwise(manualFlow);   // 或 .withoutOtherwise()：未匹配时整体 Skipped
```

路由键精确 `equals` 匹配；`caseOf` 接受任意类型键（opaque key）；重复键在构建期即报 `DUPLICATE_ROUTE_CASE`。

## 1.6 firstApplicable 与 thenOptional：Skipped 的两种消费

`firstApplicable` 是降级链：依次尝试候选，以**首个非 Skipped 结果**结束；全 Skipped 则整体 Skipped：

```java
Flow<OrderRequest, Receipt> degraded = Flow.firstApplicable(
        primeChannelFlow,        // 不适用时返回 Skipped
        backupChannelFlow,       // 不适用时返回 Skipped
        manualChannelFlow);      // 兜底
```

`thenOptional` 是可选步骤：节点总会执行，但 Skipped 不终止流水线，而是把**进入该步骤前的原值**透传给后续节点：

```java
Operation<Order, Order> applyCoupon = (context, order) -> {
    if (order.getCouponCode() == null) {
        return Outcome.skipped(Reason.of("NO_COUPON", "订单没有优惠券"));
    }
    return Outcome.accepted(order.applyCoupon());
};

Flow<Order, Receipt> flow = Flow.<Order>identity()
        .thenOptional(applyCoupon)
        .then(createReceipt);
```

| 可选节点结果 | 是否执行后续节点 | 后续节点输入 |
| :--- | :--- | :--- |
| `Accepted(value)` | 是 | `value` |
| `Skipped(reason)` | 是 | 进入可选节点前的原值 |
| `Rejected(reason)` | 否 | Rejected 向外短路 |
| `Failed(failure)` | 否 | Failed 向外短路 |

关键约束：

- 仅支持 `Operation<O, O>` 或 `Flow<O, O>`：Skipped 不携带输出，类型转换节点 `O -> N` 无法凭空提供 `N`，编译期直接拒绝。
- `thenOptional(Flow<O, O>)` 把整个子流程视为一个 optional scope：子流程最终 Skipped 时恢复的是进入子流程前的值，不是内部最后一次 Accepted 的中间值。
- 节点级 `NODE_COMPLETED` 事件仍会记录 Skipped，观测不会被伪装成 Accepted。
- 跨类型候选需显式兜底：`then(Flow.firstApplicable(candidate, defaultFlow))`，两个分支都是 `Flow<O, N>`。

三者选型：`then(op)` 标准串行步骤；`thenOptional(op)` 节点弃权但流水线继续；`firstApplicable(...)` 选择首个适用处理器，Accepted 后不再尝试其他候选。

## 1.7 容器绑定一句话（可选）

真实业务中 `Operation` 往往需要注入 Spring 托管的 DAO、RPC 客户端与事务切面。引入 `team4u-flow-bean` + `team4u-bean-spring` 后，声明时直接引用 Class 与限定符：

```java
@Component("chargePaymentOperation")
public class ChargePaymentOperation implements Operation<OrderRequest, Receipt> {
    @Autowired
    private PaymentRpcClient paymentRpcClient;

    @Override
    @Transactional(rollbackFor = Exception.class)      // 事务切面原样生效
    public Outcome<Receipt> execute(OperationContext context, OrderRequest order) {
        String txId = paymentRpcClient.charge(context.invocationId(), order.getAmount());
        return Outcome.accepted(new Receipt(order.getOrderId(), txId));
    }
}

@Configuration
@Import(Team4uBeanConfiguration.class)                  // 桥接 Spring 容器至 BeanManager
public class OrderFlowConfig {
    @Bean
    public LocalExecutable<OrderRequest, Receipt> orderExecutable() {
        // 编译期一次性解析绑定容器 Bean，运行期零反射损耗，AOP 与代理完全保留
        return BeanFlows.compile(Flow.step(ValidateOrderOperation.class)
                .then(ChargePaymentOperation.class, "chargePaymentOperation"));
    }
}
```

Service 注入 `orderExecutable` 后 `run(request).requireAccepted()` 即可。完整绑定语义见 [Bean 容器集成](flow-bean.md)。

> **停站提示**：你可以停在这里——在需要并行分支、人工审批挂起或异步驱动之前，上面这些已经够用。

---

# 2. 第 2 站（L2 进阶层）：编排能力全开

## 2.1 心智模型：线程模型

只有用到本站的 parallel / await / 异步入口，才需要读这张图：

```text
调用线程
  │ run(input) / resume(...)            同步驱动，跑完才返回
  │ runAsync(input, dispatcher)         异步入口
  ▼
Dispatcher 线程                           承担整个驱动的提交
  │ 遇到 parallel 分支 / timeout 控制需要并发时
  ▼
Worker 线程池（默认 ForkJoinPool.commonPool）
  ├── Branch("risk", riskFlow)    ┐ 真并发执行
  └── Branch("stock", stockFlow)  ┘ wait-all 全部完成后进入 join
```

死锁防御两条红线（违规配置在编译/调用期快速失败，抛 `IllegalArgumentException`）：

- 含 parallel 或 timeout 的流程，严禁把**同一个非 ForkJoinPool** 的单线程/有界池同时用作 `runAsync` 的 dispatcher 和 worker（线程饥饿自我死锁）。
- 并行分支内还嵌套 parallel 或 timeout 时，worker 必须是 `ForkJoinPool`（默认 commonPool 即可）。

## 2.2 runAsync：异步入口

```java
import java.util.concurrent.CompletionStage;

LocalExecutable<String, String> executable = Local.compile(flow);

// 返回 Java 标准 CompletionStage；dispatcher 默认 commonPool
CompletionStage<FlowResult<String>> stage = executable.runAsync("framework");
stage.thenAccept(res -> System.out.println("Async: " + res.requireAccepted()));
```

## 2.3 parallel 与 join：显式汇合的并行

```java
Branch<OrderRequest, RiskReport> risk = Branch.of("risk", riskFlow);
Branch<OrderRequest, StockReport> stock = Branch.of("stock", stockFlow);

Flow<OrderRequest, String> fanOut = Flow.<OrderRequest>parallel(risk, stock)
        .join(results -> results.allAccepted()
                .map(values -> values.get(risk).summary()
                        + "/" + values.get(stock).summary()));
```

- **true wait-all**：全部分支完成后才进入 join；分支名在同一并行块内唯一（重复报 `DUPLICATE_BRANCH`）。
- 内置策略：`allAccepted()` / `firstAccepted()` / `quorum(n)` / `homogeneousCollect()`，也可自定义 `JoinStrategy`。
- 分支内**严禁** `await` 与 `PersistentPolicy`：会破坏 wait-all 汇合合同与检查点一致性，编译期以 `PARALLEL_AWAIT` / `PARALLEL_PERSISTENT_POLICY` 快速失败。
- Local 下分支在 worker 线程池真并发；Durable 下按声明顺序串行驱动（语义一致）。

## 2.4 await / resume：挂起与恢复

```java
ResumePoint<Approval> approval = ResumePoint.named("manager-approval");

Flow<PaymentRequest, String> flow = Flow.<PaymentRequest>identity()
        .then(freezeOperation)
        .await(approval)                              // 挂起
        .then((context, resumed) -> settle(
                resumed.state(), resumed.signal()));  // Resumed<状态, 信号>

LocalExecutable<PaymentRequest, String> executable = Local.compile(flow);
FlowResult<String> first = executable.run(payment);

if (first instanceof FlowResult.Suspended) {
    Suspension<String> suspension =
            ((FlowResult.Suspended<String>) first).suspension();
    // 业务侧拿到审批结果后注入信号（Suspension 单次消费，仅可由产生它的执行器恢复）
    FlowResult<String> second = executable.resume(
            suspension, approval, new Approval(true));
    System.out.println(second.requireAccepted());
}
```

至此 `FlowResult` 三态齐了：`Completed(outcome)` / `Suspended(suspension)` / `Cancelled(executionId)`（协作式取消令牌 `Cancellation` 触发，取消不进 join）。

## 2.5 retry / timeout / recoverWith：治理三件套

```java
// retry：Failed 时按退避重试，maxAttempts 含首次执行
Flow<OrderRequest, Receipt> withRetry = Flow.step(charge)
        .retry(Retry.maxAttempts(3).withBackoff(Duration.ofMillis(200)));

// timeout：作用域超时产生稳定码 TIMEOUT 的 Failed 并终止作用域
Flow<OrderRequest, Receipt> withTimeout = Flow.step(charge)
        .timeout(Duration.ofSeconds(2));

// recoverWith：Failed 时携带 (原始输入, Failure) 进入补偿分支
Flow<OrderRequest, Receipt> withRecover = Flow.step(charge)
        .recoverWith(Flow.step((Operation<Recovery<OrderRequest>, Receipt>)
                (context, recovery) -> Outcome.accepted(
                        manualCompensate(recovery.input(), recovery.failure().code()))));
```

三件套都作用于作用域；更精细的准入网关（`policy`）与跨崩溃持久化策略（`persistentPolicy`）见[核心语义](flow-semantics.md)与 [Durable 文档](flow-durable.md)。

## 2.6 use：调用外部服务不失主上下文

需要调用外部服务又不想丢失主上下文时用 `use`（支持 Lambda 或 Class 绑定）：

```java
Flow<State, State> enriched = Flow.<State>identity().use(
        riskClient,                       // Operation<RiskReq, RiskScore> 或 RiskClientOp.class
        State::toRiskRequest,             // project: 从当前输出派生入参
        (state, score) -> state.withScore(score)); // merge: 合并原输出与新结果
```

> **停站提示**：你可以停在这里——在需要进程崩溃后从断点续跑之前，上面这些已经够用。

---

# 3. 第 3 站（L3 引擎层）：Durable 持久化执行器

## 3.1 心智模型：同一份定义，换一个执行器

**team4u-flow-durable 是独立于核心的持久化执行器组件——同一份 Flow 定义，换一个执行器，获得崩溃恢复能力。**它与 core 的关系如同 kv 之于 kv-store-jdbc：按需引入独立模块，core 不做任何改动。

```text
Flow<I, O>（你在前两站验证过的同一份定义）
    │ DurableRuntime.builder(store).build().compile(flow, flowId, flowVersion)
    ▼
DurableExecutable<I, O>（绑定 flowId:flowVersion）
    │ start / resume / recover / cancel / snapshot（另有 startAsync / resumeAsync）
    ▼
节点边界 CAS 检查点（revision 乐观锁）⇄ DurableStore（load + compareAndSet）
    ▼
DurableResult：Completed / Suspended(resumePoint) / Active(wakeAt) / Cancelled
```

## 3.2 最短路径：start / resume / recover

本站只演示入口，三个命令一段跑通（`Error` 模拟进程死亡——不提交检查点，等价崩溃）：

```java
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.durable.DurableExecutable;
import com.team4u.framework.flow.durable.DurableResult;
import com.team4u.framework.flow.durable.DurableRuntime;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Resumed;

public class DurableQuickStart {

    static final ResumePoint<Boolean> APPROVAL = ResumePoint.named("manager-approval");

    static class FlakySettle implements Operation<Resumed<String, Boolean>, String> {
        int calls = 0;

        @Override
        public Outcome<String> execute(OperationContext context,
                                       Resumed<String, Boolean> input) {
            if (++calls == 1) {
                throw new Error("simulated crash");   // 首次调用即"进程崩溃"
            }
            return Outcome.accepted(input.state() + "#settled");
        }
    }

    public static void main(String[] args) {
        Flow<String, String> flow = Flow.<String>identity().await(APPROVAL)
                .then(new FlakySettle());

        // 依赖 team4u-flow-durable；生产中替换为 JDBC 等自建 DurableStore 实现
        DurableExecutable<String, String> durable = DurableRuntime
                .builder(new InMemoryDurableStore())
                .build()
                .compile(flow, "payment-settle", 1);

        // 命令一 start：落初始检查点，驱动到挂起点
        DurableResult<String> started = durable.start("pay-0001", "order-42");
        System.out.println(((DurableResult.Suspended<String>) started).resumePoint());
        // manager-approval

        // 命令二 resume：信号先落库（两段 CAS）再续跑；结算节点首次调用即"崩溃"
        try {
            durable.resume("pay-0001", "manager-approval", true);
        } catch (Error crash) {
            System.out.println("crashed, snapshot already committed");
        }

        // 命令三 recover：进程重启后从最后提交的检查点续跑至完成
        DurableResult<String> recovered = durable.recover("pay-0001");
        System.out.println(recovered.requireAccepted()); // order-42#settled
    }
}
```

Local 与 Durable 的差异一览：

| 维度 | Local (`Local.compile`) | Durable (`DurableRuntime.compile`) |
| :--- | :--- | :--- |
| 结果类型 | `FlowResult`：Completed / Suspended / Cancelled | `DurableResult`：Completed / Suspended / **Active(wakeAt)** / Cancelled |
| 挂起句柄 | 内存 `Suspension`（不透明、单次消费） | 快照 `awaitingPoint` + `resume(executionId, pointName, signal)` |
| 崩溃恢复 | 无（进程内） | `recover(executionId)` 从最后提交快照续跑 |
| 检查点 | 无 | 节点边界 CAS（revision 乐观锁） |
| 并行分支 | worker 线程池真并发 | 按声明顺序串行驱动 |
| 退避等待 | 进程内等待 | ACTIVE+wakeAt 快照，外部调度 recover 唤醒 |
| 典型用途 | 同步编排、单机流水线、测试 | 长事务、人工审批、跨进程长时任务 |

再记两条合同即可：`invocationId = flowId:flowVersion:executionId:path` 是外部副作用的幂等键，重试与恢复重放中稳定（at-least-once）；`(flowId, flowVersion)` 变更必须递增版本号，不做旧快照迁移。

检查点协议、StateMapper 确定性编码、resume 两段提交、PersistentPolicy 状态持久化等详情见 [Durable 文档](flow-durable.md)，本站只演示入口。

> **停站提示**：你可以停在这里——在需要深入检查点协议与快照编码之前，上面这些已经够用。

---

# 4. 工具：graph 渲染流程结构

> 层级：工具 · 全层 · 模块：team4u-flow-graph

```java
import com.team4u.framework.flow.graph.FlowGraphs;

String mermaid = FlowGraphs.mermaid().render(flow.describe("payment-settle"));
System.out.println(mermaid);   // 粘贴到 Mermaid 渲染器即可看到六通道结构图

String tree = FlowGraphs.text().render(flow.describe("payment-settle"));
System.out.println(tree);      // 紧凑文本树，适合日志与评审
```

渲染只依赖 `FlowDescription`（纯只读描述模型，不含回调实例与业务值）；路由键等不可稳定呈现的值渲染为 opaque 占位符（详见[可视化文档](flow-graph.md)）。

---

# 5. 工具：testkit 写第一个测试

> 层级：工具 · 全层 · 模块：team4u-flow-test

```java
import com.team4u.framework.flow.test.*;

public class CheckoutFlowTest {

    @org.junit.Test
    public void rejectsInvalidAmount() {
        OperationStub<OrderRequest, Receipt> checkout =
                OperationStub.rejecting(Reason.of("INVALID_AMOUNT", "金额必须为正"));

        FlowResult<Receipt> result =
                LocalFixture.compile(Flow.step(checkout)).run(new OrderRequest(-1));

        FlowAssertions.assertRejected(result, "INVALID_AMOUNT");
        org.junit.Assert.assertEquals(1, checkout.callCount());
        org.junit.Assert.assertNotNull(checkout.lastInput());
    }
}
```

testkit 提供 `OperationStub` / `PolicyStub` 桩、`TraceCollector` 轨迹、`FlowAssertions` 四态断言、`LocalFixture` / `DurableFixture` 夹具与 `ParallelBarrier` 并行屏障，完整 API 见[测试文档](flow-test.md)。

---

# 6. 下一步

- [核心语义与机制](flow-semantics.md)：四态传播、八节点、Policy/Retry/Timeout、取消合同、线程池死锁防御。
- [Bean 容器集成](flow-bean.md)：Bean 声明式绑定、事务与切面代理保留、编译期解析与诊断。
- [Durable 持久化执行](flow-durable.md)：检查点、恢复、resume 两段 CAS、DurableObserver。
- [可视化与图表渲染](flow-graph.md)：FlowDescription 投影、六通道 Mermaid 图与紧凑文本树。
- [测试支持与断言](flow-test.md)：testkit 全套桩对象、断言工具与并行屏障。
- [扩展机制与 SPI](flow-extension.md)：扩展点清单与双投影 SPI。
- [实战案例](flow-sample.md)：订单风控路由降级、支付审批挂起恢复与电商履约实战。
