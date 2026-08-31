# 测试支持与断言

`team4u-flow-test` 为流程编排提供完整的测试套件（testkit），包含业务操作桩（`OperationStub`）、策略桩（`PolicyStub`）、事件轨迹收集器（`TraceCollector`）、双执行器断言工具（`FlowAssertions`）、执行夹具（`LocalFixture` / `DurableFixture`）以及并发屏障（`ParallelBarrier`）。

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

## 业务操作打桩 (OperationStub)

### 桩对象构造

```java
import com.team4u.framework.flow.model.*;
import com.team4u.framework.flow.test.OperationStub;

// 1. 自定义应答逻辑
OperationStub<String, Integer> custom = OperationStub.answering(
        (context, input) -> Outcome.accepted(input.length()));

// 2. 快捷成功
OperationStub<String, String> accepting = OperationStub.accepting(x -> x + "!");

// 3. 固定四态返回
OperationStub<String, String> rejecting = OperationStub.rejecting(Reason.of("REJECT", "拒绝"));
OperationStub<String, String> skipping = OperationStub.skipping(Reason.of("SKIP", "跳过"));
OperationStub<String, String> failing = OperationStub.failing(Failure.of("FAIL", "失败"));

// 4. 模拟异常抛出（框架统一收敛为 OPERATION_EXCEPTION Failed）
OperationStub<String, String> throwing = OperationStub.throwing(
        () -> new java.io.IOException("网络连接中断"));
```

### 调用记录与断言

桩对象线程安全地记录调用历史，支持验证入参、调用次数与幂等键：

```java
OperationStub<String, String> stub = OperationStub.accepting(x -> x);
Local.compile(Flow.step(stub)).run("in");

// 获取调用快照
List<OperationStub.Call<String>> calls = stub.calls();
OperationStub.Call<String> last = calls.get(calls.size() - 1);

System.out.println(last.input());        // "in"
System.out.println(last.invocationId()); // "local:0:<executionId>:$"
System.out.println(stub.callCount());    // 1
System.out.println(stub.lastInput());    // "in"
stub.reset();                            // 清空调用历史
```

验证 Retry 重试时使用相同幂等键的断言示例：

```java
OperationStub<String, String> flaky = OperationStub.failing(Failure.of("FAIL", "error"));
FlowResult<String> result = Local.compile(
        Flow.step(flaky).retry(Retry.maxAttempts(3))).run("input");

FlowAssertions.assertFailed(result, "FAIL");
org.junit.Assert.assertEquals(3, flaky.callCount());

// 断言三次重试的 invocationId 严格相同
String initialInvocationId = flaky.calls().get(0).invocationId();
for (OperationStub.Call<String> call : flaky.calls()) {
    org.junit.Assert.assertEquals(initialInvocationId, call.invocationId());
}
```

---

## 控制策略打桩 (PolicyStub)

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
```

---

## 事件轨迹收集 (TraceCollector)

`TraceCollector` 是线程安全的 `FlowObserver` 实现，用于捕获流程执行轨迹并在单测中进行顺序断言：

```java
import com.team4u.framework.flow.spi.FlowObserver;
import com.team4u.framework.flow.test.TraceCollector;

TraceCollector collector = new TraceCollector();
LocalExecutable<String, String> executable = Local.compile(
        flow, OperationResolver.rejecting(), collector);
executable.run("input");

// 获取全部事件
List<FlowObserver.Event> events = collector.events();

// 按事件类型过滤与断言
List<FlowObserver.Event> completedEvents =
        collector.ofType(FlowObserver.Type.NODE_COMPLETED);

// 提取事件类型序列与节点路径
List<FlowObserver.Type> types = collector.types();
List<String> paths = collector.nodePaths(FlowObserver.Type.NODE_STARTED);
```

---

## 流程断言工具 (FlowAssertions)

### Local 结果断言 (FlowResult)

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

### Durable 结果断言 (DurableResult)

```java
DurableResult<String> durableResult = executable.start("exec-01", "input");

FlowAssertions.assertCompleted(durableResult);
FlowAssertions.assertAccepted(durableResult, "expectedValue");
FlowAssertions.assertSuspended(durableResult, "manager-approval"); // 按挂起点名称断言
FlowAssertions.assertActive(durableResult);                        // 断言处于退避等待态
FlowAssertions.assertCancelled(durableResult);
```

---

## 本地执行夹具 (LocalFixture)

```java
import com.team4u.framework.flow.test.LocalFixture;

// 1. 编译夹具（内置默认拒绝解析器与空观察者）
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

## 持久化执行夹具 (DurableFixture)

```java
import com.team4u.framework.flow.test.DurableFixture;

// 1. 默认使用内存存储编译夹具
DurableFixture<String, String> fixture =
        DurableFixture.compile(flow, "payment-flow", 1);

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

## 并行重叠验证 (ParallelBarrier)

`ParallelBarrier` 基于并发计数器，用于严格验证 Local 并行分支是否真正实现了**多线程并发执行**：

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
CompletableFuture<FlowResult<String>> future = Local.compile(
        flow, OperationResolver.rejecting(), FlowObserver.noop(), workers
).runAsync("in").toCompletableFuture();

// 验证两分支在 2 秒内均到达屏障（证明真并发）
org.junit.Assert.assertTrue(barrier.awaitEntered(2000));
barrier.release(); // 放行

FlowAssertions.assertAccepted(future.get(5, TimeUnit.SECONDS), "in-leftin-right");
workers.shutdownNow();
```

---

## 推荐测试矩阵

| 验证目标 | 推荐工具组合 |
| :--- | :--- |
| **四态传播与短路** | `OperationStub` 四态工厂 + `FlowAssertions.assertAccepted / Rejected / Skipped / Failed` |
| **Retry 幂等键与次数** | `OperationStub.failing` + `calls()` 中 `invocationId()` 相等校验 + `callCount()` |
| **Policy 决策与顺序** | `PolicyStub.proceeding / deciding` + `beforeCalls() / afterCalls()` |
| **挂起与恢复 (Local)** | `LocalFixture.requireSuspension` + `resume` + `assertSuspended / assertAccepted` |
| **Durable 崩溃恢复** | `DurableFixture.withStore` + `recover` + `snapshot` |
| **resume 信号幂等** | `DurableFixture.resume` 同值/异值信号注入 + `assertActive` |
| **并行并发与隔离** | `ParallelBarrier` + `TraceCollector.ofType(PARALLEL_BRANCH_COMPLETED)` |
| **执行事件顺序** | `TraceCollector.types()` / `nodePaths(...)` |

---

## 下一步

- 了解自定义扩展点契约与双投影 SPI：[扩展机制与 SPI](flow-extension.md)
- 查看完整的端到端实战与测试用例：[实战案例](flow-sample.md)
