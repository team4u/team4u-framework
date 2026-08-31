# 快速开始

本章从依赖引入开始，用最短路径走完"类型化链 -> 可选步骤 -> 四态 -> route/parallel/await -> Local vs Durable -> 图渲染 -> 测试"。

---

# 1. 引入依赖

通过统一 BOM 引入（示例版本请对齐仓库发布版本）：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.team4u</groupId>
            <artifactId>team4u-framework</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- 核心：类型化 Flow，零第三方运行时依赖 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-flow</artifactId>
    </dependency>

    <!-- 容器绑定：支持从 Spring 或本地容器解析类型与限定符 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-flow-bean</artifactId>
    </dependency>

    <!-- Spring 环境桥接（可选，Spring 项目必需） -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-bean-spring</artifactId>
    </dependency>

    <!-- 持久化执行器（可选，支持崩溃恢复） -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-flow-durable</artifactId>
    </dependency>
</dependencies>
```

其他可选模块：`team4u-flow-graph`（渲染）、`team4u-flow-test`（测试，scope=test）。

---

# 2. 快速上手

## 2.0 新人心智模型（Mental Model）

在上手写代码前，只需理解 `team4u-flow` 的三层心智模型：

```text
┌────────────────────────────────────────────────────────┐
│ 1. 声明期 (Definition): Flow<I, O>                      │
│    纯不可变抽象语法树（AST），描述拓扑结构，本身不可直接执行        │
└──────────────────────────┬─────────────────────────────┘
                           │ 编译 (Compile / Project)
                           ▼
┌────────────────────────────────────────────────────────┐
│ 2. 编译期 (Compilation): Compiler & Resolver           │
│    静态校验拓扑、解析容器 Bean 引用、生成强类型运行时计划          │
└──────────────┬──────────────────────────┬──────────────┘
               ▼                          ▼
┌──────────────────────────────┐ ┌──────────────────────────────┐
│ 3a. Local 执行器 (内存极速)    │ │ 3b. Durable 执行器 (崩溃恢复) │
│     LocalExecutable          │ │     DurableExecutable        │
│     同步 run / 异步 runAsync │ │     CAS 节点检查点 / recover │
└──────────────────────────────┘ └──────────────────────────────┘
```

- **不可变定义**：所有组合方法（`then`、`policy` 等）均返回新的 `Flow` 实例，原实例不变，天然线程安全。
- **四态业务结果**：业务步骤返回 `Accepted`（携值成功）、`Rejected`（业务拒绝）、`Skipped`（弃权跳过）、`Failed`（技术失败），仅 `Accepted` 携带输出推进后续节点。

---

## 2.1 纯 Java 模式

业务步骤实现 `Operation<I, O>`，返回四态 `Outcome`；用 `Flow.step(...).then(...)` 组合成类型化流水线：

```java
import com.team4u.framework.flow.*;
import java.util.concurrent.CompletionStage;

public class QuickStart {

    static Operation<String, Integer> length = (context, value) ->
            Outcome.accepted(value.length());

    static Operation<Integer, String> label = (context, value) ->
            Outcome.accepted("len=" + value);

    public static void main(String[] args) throws Exception {
        // 1. Flow<I, O> 只描述结构，本身不可执行
        Flow<String, String> flow = Flow.step(length).then(label);

        // 2. 编译为 Local 可执行句柄
        LocalExecutable<String, String> executable = Local.compile(flow);

        // 3a. 同步执行
        FlowResult<String> syncResult = executable.run("team4u");
        System.out.println(syncResult.requireAccepted()); // len=6

        // 3b. 异步执行（返回 Java 标准 CompletionStage）
        CompletionStage<FlowResult<String>> asyncStage = executable.runAsync("framework");
        asyncStage.thenAccept(res -> System.out.println("Async: " + res.requireAccepted()));
    }
}
```

## 2.2 容器绑定模式（推荐）

在真实业务开发中，`Operation` 往往需要注入 Spring 托管的 DAO、RPC 客户端或带有 `@Transactional` 事务注解：

### 1. 编写 Spring 托管的 Operation

```java
@Component
public class ValidateOrderOperation implements Operation<OrderRequest, OrderRequest> {
    @Autowired
    private OrderRepository repository;

    @Override
    public Outcome<OrderRequest> execute(OperationContext context, OrderRequest order) {
        return order.getAmount() > 0 ? Outcome.accepted(order)
                : Outcome.rejected(Reason.of("INVALID_AMOUNT", "金额非法"));
    }
}

@Component("chargePaymentOperation")
public class ChargePaymentOperation implements Operation<OrderRequest, Receipt> {
    @Autowired
    private PaymentRpcClient paymentRpcClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Outcome<Receipt> execute(OperationContext context, OrderRequest order) {
        String txId = paymentRpcClient.charge(context.invocationId(), order.getAmount());
        return Outcome.accepted(new Receipt(order.getOrderId(), txId));
    }
}
```

### 2. 在 Spring 配置中编排并编译

```java
@Configuration
@Import(Team4uBeanConfiguration.class) // 桥接 Spring 容器至 BeanManager
public class OrderFlowConfig {

    @Bean
    public LocalExecutable<OrderRequest, Receipt> orderExecutable() {
        // 声明 Flow：直接引用 Class 与 Qualifier，解耦具体实例
        Flow<OrderRequest, Receipt> flow = Flow.step(ValidateOrderOperation.class)
                .then(ChargePaymentOperation.class, "chargePaymentOperation");

        // 编译期一次性解析绑定容器 Bean，运行期零反射损耗，AOP 与事务切面完全生效
        return BeanFlows.compile(flow);
    }
}
```

### 3. 业务 Service 注入直接调用

```java
@Service
public class OrderService {
    @Autowired
    private LocalExecutable<OrderRequest, Receipt> orderExecutable;

    public Receipt process(OrderRequest request) {
        return orderExecutable.run(request).requireAccepted();
    }
}
```

---

## 2.3 `thenOptional`：节点弃权但流水线继续

当一个同类型节点不适用于当前输入，但后续节点仍应继续处理时，让该节点返回真实的 `Skipped`，并通过 `thenOptional` 组合：

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

`applyCoupon` 总会执行，结果按以下规则归约：

| 可选节点结果 | 是否执行后续节点 | 后续节点输入 |
| :--- | :--- | :--- |
| `Accepted(value)` | 是 | `value` |
| `Skipped(reason)` | 是 | 进入可选节点前的原值 |
| `Rejected(reason)` | 否 | Rejected 向外短路 |
| `Failed(failure)` | 否 | Failed 向外短路 |

支持的四种入口与普通 `then` 对齐：

```java
flow.thenOptional(operationInstance);
flow.thenOptional(OptionalOperation.class);
flow.thenOptional(OptionalOperation.class, "optionalOperationBean");
flow.thenOptional(optionalSubflow);
```

关键约束：

- 仅支持 `Operation<O, O>` 或 `Flow<O, O>`。Skipped 不携带输出，类型转换节点 `O -> N` 无法凭空提供 `N`，因此会在编译期被拒绝。
- `thenOptional(Flow<O, O>)` 把整个子流程视为一个 optional scope。子流程最终 Skipped 时恢复的是进入子流程前的值，不是子流程内部最后一次 Accepted 的中间值。
- 节点级 `NODE_COMPLETED` 仍会记录 Skipped；组合层随后选择 identity 兜底并继续，业务观测不会被伪装成节点 Accepted。
- 跨类型候选需要显式提供同输出类型的兜底流程，例如 `then(Flow.firstApplicable(candidate, defaultFlow))`，其中两个分支都必须是 `Flow<O, N>`。

`thenOptional` 与 `firstApplicable` 解决不同问题：前者无论节点 Accepted 还是 Skipped 都继续外层流水线；后者在 Skipped 时尝试下一个候选，并以首个非 Skipped 结果结束候选选择。

## 2.4 组合与上下文调用要点

- `then` 前后类型严格推导：`Flow<String, Integer>.then(Operation<Integer, String>)` 得到 `Flow<String, String>`，不匹配直接编译错误。
- 所有组合方法返回新的 `Flow` 实例，定义不可变、线程安全。
- 需要调用外部服务又不想丢失主上下文时用 `use`（支持 Lambda 或 Class 绑定）：

```java
Flow<State, State> enriched = Flow.<State>identity().use(
        riskClient,                       // Operation<RiskReq, RiskScore> 或 RiskClientOp.class
        State::toRiskRequest,             // project: 从当前输出派生入参
        (state, score) -> state.withScore(score)); // merge: 合并原输出与新结果
```

---

# 3. 四态 Outcome

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

传播要点：普通 `then` 仅 Accepted 推进；`thenOptional` 在局部边界把 Skipped 处理为原值透传；Rejected 终止透传；Skipped 还可被 `firstApplicable` 消费；Failed 触发 `recoverWith`/`retry`（详见[核心语义](flow-semantics.md)）。测试时用 testkit 的 `FlowAssertions.assertAccepted/assertRejected/...` 一行断言。

---

# 4. route / parallel / await

## 4.1 route：条件路由

```java
Flow<OrderRequest, Receipt> routed = Flow
        .route((Operation<OrderRequest, String>) (context, order) ->
                Outcome.accepted(order.getChannel()))
        .caseOf("ALIPAY", alipayFlow)
        .caseOf("WECHAT", wechatFlow)
        .otherwise(manualFlow);          // 或 .withoutOtherwise()：未匹配整体 Skipped
```

路由键精确 `equals` 匹配；`caseOf` 接受任意类型键（opaque key）。

## 4.2 firstApplicable：降级链

```java
Flow<OrderRequest, Receipt> degraded = Flow.firstApplicable(
        primeChannelFlow,        // 不适用时返回 Skipped
        backupChannelFlow,       // 不适用时返回 Skipped
        manualChannelFlow);      // 兜底
// 首个非 Skipped 的分支即整体结果；全部 Skipped 则整体 Skipped
```

这里的候选分支不会像 `thenOptional` 一样在 Accepted 后继续尝试其他候选；二者分别表达“选择首个适用处理器”和“可选处理后继续流水线”。

## 4.3 parallel：显式 join 的并行

```java
Branch<OrderRequest, RiskReport> risk = Branch.of("risk", riskFlow);
Branch<OrderRequest, StockReport> stock = Branch.of("stock", stockFlow);

Flow<OrderRequest, String> fanOut = Flow.<OrderRequest>parallel(risk, stock)
        .join(results -> results.allAccepted()
                .map(values -> values.get(risk).summary()
                        + "/" + values.get(stock).summary()));
```

- wait-all：全部分支完成后才进入 join；分支名唯一；分支内不能 await / PersistentPolicy。
- 内置策略：`allAccepted()` / `firstAccepted()` / `quorum(n)` / `homogeneousCollect()`。
- Local 下分支真并发（worker 线程池）；Durable 下按声明顺序串行驱动（语义一致）。

## 4.4 await：挂起与恢复

```java
ResumePoint<Approval> approval = ResumePoint.named("manager-approval");

Flow<PaymentRequest, String> flow = Flow.<PaymentRequest>identity()
        .then(freezeOperation)
        .await(approval)                                    // 挂起
        .then((context, resumed) -> settle(
                resumed.state(), resumed.signal()));        // resumed.state/signal

LocalExecutable<PaymentRequest, String> executable = Local.compile(flow);
FlowResult<String> first = executable.run(payment);

if (first instanceof FlowResult.Suspended) {
    Suspension<String> suspension =
            ((FlowResult.Suspended<String>) first).suspension();
    // 业务侧拿到审批结果后注入信号（Suspension 单次消费）
    FlowResult<String> second = executable.resume(
            suspension, approval, new Approval(true));
    System.out.println(second.requireAccepted());
}
```

---

# 5. Local vs Durable

| 维度 | Local (`Local.compile`) | Durable (`DurableRuntime.compile`) |
| :--- | :--- | :--- |
| 结果类型 | `FlowResult`：Completed / Suspended / Cancelled | `DurableResult`：Completed / Suspended / **Active(wakeAt)** / Cancelled |
| 挂起句柄 | 内存 `Suspension`（不透明、单次消费） | 快照 `awaitingPoint` + `resume(executionId, pointName, signal)` |
| 崩溃恢复 | 无（进程内） | `recover(executionId)` 从最后提交快照续跑 |
| 检查点 | 无 | 节点边界 CAS（revision 乐观锁） |
| 并行分支 | worker 线程池真并发 | 按声明顺序串行驱动 |
| 退避等待 | 进程内等待 | ACTIVE+wakeAt 快照，外部调度 recover 唤醒 |
| 典型用途 | 同步编排、单机流水线、测试 | 长事务、人工审批、跨进程长时任务 |

同一份定义切换到 Durable：

```java
DurableRuntime runtime = DurableRuntime.builder(new InMemoryDurableStore()).build();
DurableExecutable<PaymentRequest, String> durable =
        runtime.compile(flow, "payment-settle", 1);

DurableResult<String> started = durable.start("pay-20240101-0001", payment);
// started 为 Suspended(resumePoint=manager-approval)

DurableResult<String> resumed = durable.resume(
        "pay-20240101-0001", "manager-approval", new Approval(true));
// resumed 为 Completed[Accepted[...]]
```

Durable 要点：`invocationId = flowId:flowVersion:executionId:path` 作为外部副作用幂等键；resume 信号先落库（两段 CAS）；`(flowId, flowVersion)` 变更必须递增版本号，不做旧快照迁移（详见 [Durable 文档](flow-durable.md)）。

---

# 6. graph：渲染流程结构

```java
import com.team4u.framework.flow.graph.FlowGraphs;

String mermaid = FlowGraphs.mermaid().render(flow.describe("payment-settle"));
System.out.println(mermaid);   // 粘贴到 Mermaid 渲染器即可看到六通道结构图

String tree = FlowGraphs.text().render(flow.describe("payment-settle"));
System.out.println(tree);      // 紧凑文本树，适合日志与评审
```

渲染只依赖 `FlowDescription`（纯只读描述模型，不含回调实例与业务值）；路由键等不可稳定呈现的值渲染为 opaque 占位符（详见 [可视化文档](flow-graph.md)）。

---

# 7. testkit：写第一个测试

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

testkit 提供 `OperationStub`/`PolicyStub` 桩、`TraceCollector` 轨迹、`FlowAssertions` 四态断言、`LocalFixture`/`DurableFixture` 夹具与 `ParallelBarrier` 并行屏障，完整 API 见[测试文档](flow-test.md)。

---

# 8. 新手避坑与核心规则 FAQ

### Q1: 为什么 `Flow<I, O>` 不能直接 `.run()`，必须先 `Local.compile(flow)`？
> **答**：`Flow` 是纯逻辑拓扑定义（不可变 AST），只描述“流程由哪些步骤组成”。调用 `Local.compile` 或 `BeanFlows.compile` 阶段会完成静态结构校验、从 Spring 容器解析单例 Bean 绑定并优化执行帧栈。编译产物 `LocalExecutable` 是线程安全的高性能单例，生产中应以 Spring `@Bean` 单例托管并在 Service 中重复调用。

### Q2: `then`、`thenOptional` 与 `firstApplicable` 该怎么选？
> **答**：
> - **`then(op)`**：标准串行步骤。**仅由 Accepted 推进**；若返回 `Skipped`、`Rejected` 或 `Failed`，流水线立即短路终止。
> - **`thenOptional(op)`**：可选步骤（仅限 `O -> O`）。节点返回 `Skipped` 时**保留进入步骤前的原值继续执行后续流水线**（`Rejected`/`Failed` 仍短路）。
> - **`firstApplicable(flowA, flowB)`**：候选降级链。依次尝试候选分支，遇到 `Skipped` 尝试下一个分支，以**首个非 Skipped 结果**作为整体输出。

### Q3: 为什么 Parallel 并行分支内严禁 `await` 与 `PersistentPolicy`？
> **答**：`Parallel` 采用严密的 wait-all 汇合合同。若分支内部允许挂起或持有跨重启的独立持久化状态，会导致多分支并发提交检查点时的 CAS 版本风暴与帧栈状态不一致。因此框架在静态编译期就会以 `PARALLEL_AWAIT` / `PARALLEL_PERSISTENT_POLICY` 快速失败拒绝。

### Q4: 业务代码抛出未捕获异常（如 NPE / RPC 异常）会怎样？
> **答**：引擎绝不会让异常直接逃逸。所有未受检异常会被底层自动捕获并转换为携带标准错误码（如 `OPERATION_EXCEPTION`）的 `Outcome.Failed`。该失败可以被外层的 `.retry(...)` 自动重试，或被 `.recoverWith(...)` 捕获进行补偿降级。

### Q5: 自定义线程池时有哪些关键约束？
> **答**：当流程包含 `parallel` 并行或 `timeout` 超时控制时，**严禁将同一个单线程池（`newSingleThreadExecutor`）或有界线程池同时用于 `runAsync` 的 dispatcher 和底层 worker**。否则会触发线程饥饿导致自我死锁。框架内置了死锁防御检测，违规配置时会立即抛出 `IllegalArgumentException` 快速失败。

---

# 9. 下一步

- [核心语义与机制](flow-semantics.md)：四态传播、八节点、Policy/Retry/Timeout、取消合同、线程池死锁防御。
- [Bean 容器集成](flow-bean.md)：Bean 声明式绑定、事务与切面代理保留、编译期解析与诊断。
- [Durable 持久化执行](flow-durable.md)：检查点、恢复、resume 两段 CAS、DurableObserver。
- [可视化与图表渲染](flow-graph.md)：FlowDescription 投影、六通道 Mermaid 图与紧凑文本树。
- [测试支持与断言](flow-test.md)：testkit 全套桩对象、断言工具与并行屏障。
- [实战案例](flow-sample.md)：订单风控路由降级、支付审批挂起恢复与电商履约实战。
