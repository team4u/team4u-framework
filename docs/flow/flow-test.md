# 测试支持与测试套件

`team4u-flow-test` 为流程编排提供开箱即用的测试套件（testkit），包含业务操作桩（`OperationStub`）、策略桩（`PolicyStub`）、事件轨迹收集器（`TraceCollector`）、双执行器断言库（`FlowAssertions`）、测试执行夹具（`LocalFixture` / `DurableFixture`）以及并发重叠验证屏障（`ParallelBarrier`）。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-test</artifactId>
    <scope>test</scope>
</dependency>
```

---

## 业务操作打桩 (`OperationStub`)

### 桩对象工厂方法

```java
import com.team4u.framework.flow.model.*;
import com.team4u.framework.flow.test.OperationStub;

// 1. 自定义应答逻辑
OperationStub<String, Integer> custom = OperationStub.answering(
        (context, input) -> Outcome.accepted(input.length()));

// 2. 快捷成功产出
OperationStub<String, String> accepting = OperationStub.accepting(x -> x + "!");

// 3. 固定四态返回
OperationStub<String, String> rejecting = OperationStub.rejecting(Reason.of("REJECT", "业务拒绝"));
OperationStub<String, String> skipping  = OperationStub.skipping(Reason.of("SKIP", "弃权跳过"));
OperationStub<String, String> failing   = OperationStub.failing(Failure.of("FAIL", "系统失败"));

// 4. 模拟异常抛出（框架统一收敛为 OPERATION_EXCEPTION Failed）
OperationStub<String, String> throwing  = OperationStub.throwing(
        () -> new java.io.IOException("网络连接中断"));
```

### 调用历史记录与断言

桩对象线程安全地记录调用历史，支持断言入参、调用次数与幂等键：

```java
OperationStub<String, String> stub = OperationStub.accepting(x -> x);
Local.compile(Flow.step(stub)).run("input-data");

// 获取调用快照
List<OperationStub.Call<String>> calls = stub.calls();
OperationStub.Call<String> last = calls.get(calls.size() - 1);

System.out.println(last.input());        // "input-data"
System.out.println(last.invocationId()); // "local:0:<executionId>:$"
System.out.println(last.attempt());      // 1（重试场景下递增）
System.out.println(stub.callCount());    // 1
System.out.println(stub.lastInput());    // "input-data"
stub.reset();                            // 清空调用历史
```

#### 验证重试策略下使用相同幂等键的测试示例

```java
OperationStub<String, String> flaky = OperationStub.failing(Failure.of("FAIL", "error"));
FlowRetryPolicy<String> retryPolicy = FlowRetryPolicy.fixed(3, 0);

// 通过 persistentPolicy 挂载重试策略（策略键直接使用输入对象）
FlowResult<String> result = Local.compile(
                Flow.step(flaky).persistentPolicy(retryPolicy, Function.identity()))
        .run("test-in");

FlowAssertions.assertFailed(result, "FAIL");
org.junit.Assert.assertEquals(3, flaky.callCount());

// 断言三次尝试的 invocationId 严格相同（外部防重关键）
String firstInvocationId = flaky.calls().get(0).invocationId();
for (OperationStub.Call<String> call : flaky.calls()) {
    org.junit.Assert.assertEquals(firstInvocationId, call.invocationId());
}
```

---

## 控制策略打桩 (`PolicyStub`)

```java
import com.team4u.framework.flow.test.PolicyStub;

// 1. 固定放行策略桩
PolicyStub<String> proceeding = PolicyStub.proceeding();

// 2. 自定义阻断决策桩
PolicyStub<String> blocking = PolicyStub.deciding(
        Gate.reject(Reason.of("RATE_LIMITED", "已被限流")));

// 3. 执行并校验 before / after 拦截记录
Local.compile(Flow.<String>identity().policy(proceeding, val -> val)).run("key");

System.out.println(proceeding.beforeCount()); // 1
System.out.println(proceeding.afterCount());  // 1

// 也可逐条检查调用记录（含 attempt 与键）
// proceeding.beforeCalls().get(0).attempt();
// proceeding.afterCalls().get(0).completion().kind();
```

### 持久化策略打桩（PersistentPolicyStub）

`PersistentPolicyStub<K>` 是固定次数重试的 `PersistentPolicy` 测试桩，通过
`PersistentPolicyStub.counting(maxAttempts, backoff)` 创建。它以不可变 `Integer`（当前轮次，
从 1 起计）为策略状态：目标步骤 `Failed` 且未达到 `maxAttempts` 时按 `retryAt(now + backoff)`
退避重试；其余情形（Accepted / Rejected / Skipped 或次数耗尽）直接返回当前状态，
适合在测试中快速搭建“失败 N 次后成功/耗尽”的编排：

```java
import com.team4u.framework.flow.test.PersistentPolicyStub;
import java.time.Duration;

// 1. 失败重试直到次数耗尽：步骤共尝试 3 次（含初试），退避 0ms
OperationStub<String, String> failing = OperationStub.failing(Failure.of("STUB_FAIL", "stub failure"));
Flow<String, String> retryFlow = Flow.step(failing)
        .persistentPolicy(PersistentPolicyStub.<String>counting(3, Duration.ZERO), s -> s);

FlowAssertions.assertFailed(LocalFixture.compile(retryFlow).run("in"), "STUB_FAIL");
org.junit.Assert.assertEquals(3, failing.callCount());

// 2. 成功路径：首轮即通过，不重试
OperationStub<String, String> ok = OperationStub.accepting(x -> x + "!");
Flow<String, String> okFlow = Flow.step(ok)
        .persistentPolicy(PersistentPolicyStub.<String>counting(3, Duration.ofHours(1)), s -> s);

FlowAssertions.assertAccepted(LocalFixture.compile(okFlow).run("in"), "in!");
org.junit.Assert.assertEquals(1, ok.callCount());
```

`maxAttempts` 必须为正数（包含初试，>= 1），违反时构造期抛出 `IllegalArgumentException`。

### 重试状态编解码（FlowRetryStateMapper）

`team4u-flow-retry` 模块提供 `FlowRetryStateMapper`：`FlowRetryState` 的手工
`StateMapper` 实现（codecId 为 `flow-retry-attempt`、版本 1，单例 `INSTANCE`），
以十进制文本编码唯一的 `attempt` 字段，无第三方序列化依赖。在 Durable 单测中可
直接编解码 `policy:<path>` 槽位里的重试状态，无需业务侧自行编写序列化适配：

```java
import com.team4u.framework.flow.durable.snapshot.CompositeStateMapper;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.durable.Durable;
import com.team4u.framework.flow.retry.FlowRetryPolicy;
import com.team4u.framework.flow.retry.FlowRetryStateMapper;

// 将 FlowRetryStateMapper 作为 Durable 引擎的默认编解码器，重试状态获得开箱即用的持久化能力
DurableExecutable<String, String> executable = Durable.builder(new InMemoryDurableStore())
        .stateMapper(CompositeStateMapper.withDefault(FlowRetryStateMapper.INSTANCE))
        .build()
        .compile(Flow.step(flakyOp).persistentPolicy(
                FlowRetryPolicy.fixed(3, 20), Function.identity()), "durable-retry", 1);
```

> [!NOTE]
> `FlowRetryStateMapper` 位于 `team4u-flow-retry` 模块（依赖 `team4u-flow-durable` 的
> `StateMapper` SPI），不在 `team4u-flow-test` testkit 内；需要编解码重试状态槽位时
> 需额外引入 `team4u-flow-retry` 依赖。

---

## 事件轨迹收集 (`TraceCollector`)

`TraceCollector` 是线程安全的 `FlowObserver` 实现（内部基于 `CopyOnWriteArrayList`，可安全接收多线程并发到达的并行分支事件），用于捕获流程执行轨迹并在单测中进行顺序断言：

```java
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.test.TraceCollector;

TraceCollector collector = new TraceCollector();
LocalExecutable<String, String> executable = Local.from(flow)
        .observer(collector)
        .compile();
executable.run("input");

// 获取全部事件列表
List<FlowObserver.Event> events = collector.events();

// 按事件类型过滤
List<FlowObserver.Event> completedEvents =
        collector.ofType(FlowObserver.Type.NODE_COMPLETED);

// 提取事件类型序列与节点路径列表
List<FlowObserver.Type> types = collector.types();
List<String> paths = collector.nodePaths(FlowObserver.Type.NODE_STARTED);
```

---

## 流程断言库 (`FlowAssertions`)

### Local 执行结果断言 (`FlowResult`)

```java
import com.team4u.framework.flow.test.FlowAssertions;

FlowResult<String> result = Local.compile(flow).run("input");

FlowAssertions.assertCompleted(result);                        // 断言已完成并返回 Outcome
FlowAssertions.assertAccepted(result, "expectedValue");        // 断言 Accepted 且值相等
FlowAssertions.assertRejected(result, "INSUFFICIENT_BALANCE"); // 断言 Rejected 且校验原因码
FlowAssertions.assertSkipped(result, "NO_APPLICABLE");         // 断言 Skipped 且校验原因码
FlowAssertions.assertFailed(result, "OPERATION_EXCEPTION");    // 断言 Failed 且校验失败码
FlowAssertions.assertSuspended(result, approvalPoint);         // 断言挂起且挂起点匹配
FlowAssertions.assertCancelled(result);                        // 断言流程已取消
```

### Durable 执行结果断言 (`DurableResult`)

```java
DurableResult<String> durableResult = executable.start("exec-01", "input");

FlowAssertions.assertCompleted(durableResult);
FlowAssertions.assertAccepted(durableResult, "expectedValue");
FlowAssertions.assertSuspended(durableResult, "manager-approval"); // 按挂起点名称断言
FlowAssertions.assertActive(durableResult);                        // 断言处于退避等待态
FlowAssertions.assertCancelled(durableResult);
```

---

## 本地执行夹具 (`LocalFixture`)

```java
import com.team4u.framework.flow.test.LocalFixture;

// 1. 编译夹具
LocalFixture<String, String> fixture = LocalFixture.compile(flow);

// 2. 挂起与恢复测试
ResumePoint<String> approval = ResumePoint.named("manager-approval");
Flow<String, String> approvalFlow = Flow.<String>identity()
        .await(approval)
        .then((context, resumed) -> Outcome.accepted(resumed.signal()));

LocalFixture<String, String> approvalFixture = LocalFixture.compile(approvalFlow);
Suspension<String> suspension = approvalFixture.requireSuspension("input");

FlowResult<String> finalResult = approvalFixture.resume(suspension, approval, "approved");
FlowAssertions.assertAccepted(finalResult, "approved");
```

---

## 持久化执行夹具 (`DurableFixture`)

```java
import com.team4u.framework.flow.test.DurableFixture;

// 1. 默认使用内存存储编译夹具（支持极简单测 compile(flow)，默认 flowId="test", flowVersion=1）
DurableFixture<String, String> fixture = DurableFixture.compile(flow);
// 或显式指定业务标识：
// DurableFixture<String, String> fixture = DurableFixture.compile(flow, "payment-flow", 1);

// 2. 驱动命令
fixture.start("exec-1", "input");
fixture.recover("exec-1");
fixture.resume("exec-1", approvalPoint, "approved");
fixture.cancel("exec-1");

// 3. 模拟崩溃与重放测试
DurableFixture<String, String> crashFixture =
        DurableFixture.withStore(crashProbeStore, flow, "payment-flow", 1);
try {
    crashFixture.start("exec-2", "input");
} catch (RuntimeException simulatedCrash) {
    // 模拟崩溃发生，快照已成功提交入库
}

// 重新加载并恢复执行
DurableResult<String> recovered =
        DurableFixture.compile(flow, "payment-flow", 1).recover("exec-2");
FlowAssertions.assertCompleted(recovered);
```

---

## 并行重叠验证屏障 (`ParallelBarrier`)

`ParallelBarrier` 基于并发计数器与等待屏障，用于严格验证 Local 并行分支是否真正实现了**多线程并发执行（而非串行推进）**：

```java
import com.team4u.framework.flow.test.ParallelBarrier;
import java.util.concurrent.*;

ParallelBarrier barrier = new ParallelBarrier(2);

Branch<String, String> left = Branch.of("left", (context, input) -> {
    barrier.enter(); // 登记并阻塞等待其他分支到达
    return Outcome.accepted(input + "-left");
});

Branch<String, String> right = Branch.of("right", (context, input) -> {
    barrier.enter();
    return Outcome.accepted(input + "-right");
});

Flow<String, String> flow = Flow.<String>parallel(left, right)
        .join(results -> results.allAccepted()
                .map(values -> values.get(left) + values.get(right)));

ExecutorService workers = Executors.newFixedThreadPool(2);

// 必须使用 runAsync 异步驱动，避免阻塞主测试线程
CompletableFuture<FlowResult<String>> future = Local.from(flow)
        .executor(workers)
        .compile()
        .runAsync("in")
        .toCompletableFuture();

// 验证两分支在 2 秒内均到达屏障（证明真并发重叠）
org.junit.Assert.assertTrue(barrier.awaitEntered(2000));
barrier.release(); // 放行

FlowAssertions.assertAccepted(future.get(5, TimeUnit.SECONDS), "in-leftin-right");
workers.shutdownNow();
```

---

## 推荐测试矩阵

| 验证目标 | 推荐工具组合 | 核心断言方法 |
| :--- | :--- | :--- |
| **四态传播与短路** | `OperationStub` 四态工厂 | `FlowAssertions.assertAccepted / Rejected / Skipped / Failed` |
| **Retry 幂等键与重试次数** | `OperationStub.failing` + `calls()` | `call.invocationId()` 相等校验 + `callCount()` |
| **Policy 拦截与顺序** | `PolicyStub.proceeding / deciding` | `beforeCount()` / `afterCount()` |
| **Local 挂起与恢复** | `LocalFixture.requireSuspension` + `resume` | `assertSuspended` + `assertAccepted` |
| **Durable 崩溃恢复** | `DurableFixture.withStore` + `recover` | `assertCompleted` + `snapshot()` |
| **resume 信号幂等** | `DurableFixture.resume` 同值/异值信号注入 | `assertActive` / `assertCompleted` |
| **多线程真并发** | `ParallelBarrier` + `TraceCollector` | `barrier.awaitEntered()` + `ofType(PARALLEL_BRANCH_COMPLETED)` |
| **全链路事件顺序** | `TraceCollector.types()` | `org.junit.Assert.assertEquals(expectedTypes, types)` |

---

## 关联章节与进一步阅读

- 了解自定义扩展点契约与双投影 SPI：[扩展机制与 SPI 开发指南](flow-extension.md)
- 查看完整的端到端实战与测试用例：[实战案例库与生产模式](flow-sample.md)
