# 快速开始

本章从依赖引入开始，用最短路径走完"类型化链 -> 四态 -> route/parallel/await -> Local vs Durable -> 图渲染 -> 测试"。

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

`team4u-flow` 支持**纯 Java 函数**与 **Bean 容器绑定**两种使用方式。

## 2.1 纯 Java 模式

业务步骤实现 `Operation<I, O>`，返回四态 `Outcome`；用 `Flow.step(...).then(...)` 组合成类型化流水线：

```java
import com.team4u.framework.flow.*;

public class QuickStart {

    static Operation<String, Integer> length = (context, value) ->
            Outcome.accepted(value.length());

    static Operation<Integer, String> label = (context, value) ->
            Outcome.accepted("len=" + value);

    public static void main(String[] args) {
        // Flow<I, O> 只描述结构，本身不可执行
        Flow<String, String> flow = Flow.step(length).then(label);

        // 编译为 Local 可执行并同步运行
        LocalExecutable<String, String> executable = Local.compile(flow);
        FlowResult<String> result = executable.run("team4u");

        // FlowResult.requireAccepted(): Completed/Accepted 时返回输出
        System.out.println(result.requireAccepted()); // len=7
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

## 2.3 组合与上下文调用要点

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

传播要点：`then` 仅 Accepted 推进；Rejected 终止透传；Skipped 可被 `firstApplicable` 消费；Failed 触发 `recoverWith`/`retry`（详见[核心语义](flow-semantics.md)）。测试时用 testkit 的 `FlowAssertions.assertAccepted/assertRejected/...` 一行断言。

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

# 8. 下一步

- [核心语义与机制](flow-semantics.md)：四态传播、八节点、Policy/Retry/Timeout、取消合同。
- [Bean 容器集成](flow-bean.md)：Bean 声明式绑定、事务与切面代理保留、编译期解析与诊断。
- [Durable 持久化执行](flow-durable.md)：检查点、恢复、resume 两段 CAS。
- [实战案例](flow-sample.md)：订单风控路由与支付审批恢复的完整示例。
