# 测试支持与断言

`team4u-flow-test` 提供覆盖四态 Outcome、Local/Durable 双执行器的测试桩、断言、夹具与并行屏障。

---

# 1. 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-test</artifactId>
    <scope>test</scope>
</dependency>
```

依赖 `team4u-flow`、`team4u-flow-durable` 与 JUnit 4。

---

# 2. OperationStub：四态 Operation 桩

```java
import com.team4u.framework.flow.test.OperationStub;
```

## 2.1 构造

```java
// 自定义应答函数：按上下文与入参产生 Outcome
OperationStub<String, Integer> custom = OperationStub.answering(
        (context, input) -> Outcome.accepted(input.length()));

// 每次调用对入参应用函数并返回 Accepted
OperationStub<String, String> accepting = OperationStub.accepting(x -> x + "!");

// 固定返回三态
OperationStub<String, String> rejecting = OperationStub.rejecting(
        Reason.of("NOPE", "nope"));
OperationStub<String, String> skipping = OperationStub.skipping(
        Reason.of("SKIP", "skip"));
OperationStub<String, String> failing = OperationStub.failing(
        Failure.of("BOOM", "boom"));

// 每次调用抛出 supplier 新建的异常（框架转为 OPERATION_EXCEPTION Failed）
OperationStub<String, String> throwing = OperationStub.throwing(
        () -> new java.io.IOException("gateway down"));
```

## 2.2 调用记录

桩按到达顺序记录每次调用的 `input`、稳定 `invocationId` 与重试 `attempt`（上下文未暴露 attempt 时记 0）：

```java
OperationStub<String, String> stub = OperationStub.accepting(x -> x);
Local.compile(Flow.step(stub)).run("in");

java.util.List<OperationStub.Call<String>> calls = stub.calls(); // 不可变快照
OperationStub.Call<String> last = calls.get(calls.size() - 1);
last.input();          // "in"
last.invocationId();   // "local:0:<executionId>:$/0"
last.attempt();        // 0（重试场景下为重试序号）

stub.callCount();      // 调用次数
stub.lastInput();      // 最近一次入参，无调用时 null
stub.reset();          // 清空调用记录（不影响应答行为）
```

验证 retry 使用稳定幂等键的典型断言：

```java
OperationStub<String, String> flaky = OperationStub.failing(Failure.of("F", "f"));
FlowResult<String> result = Local.compile(
        Flow.step(flaky).retry(Retry.maxAttempts(3))).run("x");

FlowAssertions.assertFailed(result, "F");
org.junit.Assert.assertEquals(3, flaky.callCount());
String first = flaky.calls().get(0).invocationId();
for (OperationStub.Call<String> call : flaky.calls()) {
    org.junit.Assert.assertEquals(first, call.invocationId()); // 重放幂等键不变
}
```

---

# 3. PolicyStub：Policy 桩

```java
import com.team4u.framework.flow.test.PolicyStub;
```

```java
// 固定放行
PolicyStub<String> proceeding = PolicyStub.proceeding();

// 以指定 Gate 决策构造（Reject/Fail 直接终出对应 Outcome）
PolicyStub<String> blocking = PolicyStub.deciding(
        Gate.reject(Reason.of("RATE_LIMITED", "限流")));

// 运行中更新固定决策
proceeding.alwaysDecide(Gate.fail(Failure.of("BREAKER_OPEN", "熔断")));
```

记录 before/after 调用（attempt、策略键、完成摘要）：

```java
PolicyStub<String> policy = PolicyStub.proceeding();
Local.compile(
        Flow.<String>identity().policy(policy, value -> value)).run("k");

policy.beforeCalls();   // List<BeforeCall<String>>：attempt() 与 key()
policy.afterCalls();    // List<AfterCall<String>>：attempt()、key() 与 completion()
policy.beforeCount();
policy.afterCount();
policy.reset();
```

`AfterCall.completion()` 返回 `Completion`（四态摘要，无输出值），可断言 `completion().kind()` 与 `reason()/failure()`。

---

# 4. TraceCollector：事件轨迹收集

```java
import com.team4u.framework.flow.test.TraceCollector;
```

线程安全的 `FlowObserver` 实现（框架隔离 observer 异常，Parallel 分支事件来自多工作线程）：

```java
TraceCollector collector = new TraceCollector();
LocalExecutable<String, String> executable = Local.compile(flow,
        OperationResolver.rejecting(), collector);
executable.run("in");

collector.events();       // 全部事件（不可变快照，按到达顺序）
collector.eventCount();
collector.ofType(FlowObserver.Type.NODE_COMPLETED); // 按类型过滤
collector.types();       // 类型序列（断言顺序）
collector.nodePaths(FlowObserver.Type.NODE_STARTED); // 指定类型事件的节点 path 列表
collector.clear();
```

常用事件类型：`FLOW_STARTED/FLOW_COMPLETED/FLOW_SUSPENDED/FLOW_CANCELLED`、`NODE_STARTED/NODE_COMPLETED`、`ROUTE_SELECTED`、`FALLBACK_SELECTED`、`POLICY_BEFORE/POLICY_AFTER/POLICY_WAITING`、`PARALLEL_STARTED/PARALLEL_BRANCH_COMPLETED/PARALLEL_JOINED`。节点 path 仅用于单次产物内断言，不建议跨 Flow 结构变更比较。

---

# 5. FlowAssertions：四态与双执行器断言

`FlowAssertions` 对 `FlowResult`（Local）与 `DurableResult`（Durable）提供镜像 overload，失败时抛出含期望与实际的可读 `AssertionError`。

## 5.1 Local（FlowResult）

```java
import com.team4u.framework.flow.test.FlowAssertions;

FlowResult<String> result = Local.compile(flow).run("in");

FlowAssertions.assertCompleted(result);                        // Completed，返回 Outcome
FlowAssertions.assertAccepted(result, "expected");             // Completed/Accepted，值相等，返回值
FlowAssertions.assertRejected(result, "INSUFFICIENT_BALANCE"); // 返回 Reason
FlowAssertions.assertSkipped(result, "NO_APPLICABLE");         // 返回 Reason
FlowAssertions.assertFailed(result, "OPERATION_EXCEPTION");    // 返回 Failure
FlowAssertions.assertSuspended(result, approvalPoint);         // 挂起点匹配，返回 Suspension
FlowAssertions.assertCancelled(result);                        // 返回 executionId
```

## 5.2 Durable（DurableResult）

```java
DurableResult<String> durable = executable.start("e1", "in");

FlowAssertions.assertCompleted(durable);                       // 返回 Outcome
FlowAssertions.assertAccepted(durable, "expected");
FlowAssertions.assertSuspended(durable, "manager-approval");   // 按 pointName 匹配
FlowAssertions.assertActive(durable);                          // Active 且必须携带 wakeAt
FlowAssertions.assertActive(durable, false);                   // Active，wakeAt 可选
FlowAssertions.assertCancelled(durable);                       // 返回 DurableSnapshot
```

---

# 6. LocalFixture：Local 执行夹具

```java
import com.team4u.framework.flow.test.LocalFixture;
```

```java
// 默认 fixture：rejecting resolver + noop observer
LocalFixture<String, String> fixture = LocalFixture.compile(flow);

// 注入 TraceCollector
LocalFixture<String, String> traced = LocalFixture.compile(flow, collector);

// 全参：显式 resolver 与 observer
LocalFixture<String, String> full = LocalFixture.compile(flow, resolver, observer);

// 包装已编译 executable
LocalFixture<String, String> wrapped = LocalFixture.of(executable);

fixture.run("in");                       // FlowResult<O>
fixture.requireAccepted("in");           // 要求 Completed/Accepted，返回输出
fixture.requireSuspension("in");         // 要求 Suspended，返回 Suspension
fixture.resume(suspension, point, sig);  // 恢复挂起执行
fixture.executable();                    // 底层 LocalExecutable
```

挂起-恢复的典型测试：

```java
ResumePoint<String> approval = ResumePoint.named("approval");
LocalFixture<String, String> fixture = LocalFixture.compile(
        Flow.<String>identity().await(approval)
                .then((context, resumed) -> Outcome.accepted(resumed.signal())));

Suspension<String> suspension = fixture.requireSuspension("in");
FlowResult<String> result = fixture.resume(suspension, approval, "yes");
FlowAssertions.assertAccepted(result, "yes");
```

---

# 7. DurableFixture：Durable 执行夹具

```java
import com.team4u.framework.flow.test.DurableFixture;
```

```java
// 默认：InMemoryDurableStore + rejecting resolver
DurableFixture<String, String> fixture =
        DurableFixture.compile(flow, "smoke-flow", 1);

// 指定 store（注入冲突/崩溃探针）
DurableFixture<String, String> withStore =
        DurableFixture.withStore(customStore, flow, "smoke-flow", 1);

// 指定 runtime（自定义 stateMapper/observer）
DurableFixture<String, String> withRuntime =
        DurableFixture.withRuntime(DurableRuntime.builder(store)
                .stateMapper(mapper).build(), flow, "smoke-flow", 1);

// 包装已编译 executable
DurableFixture<String, String> wrapped = DurableFixture.of(executable);

fixture.start("exec-1", "in");        // 重复 start 抛 EXECUTION_EXISTS
fixture.recover("exec-1");            // 从最后提交快照续跑（非 ACTIVE 拒绝）
fixture.resume("exec-1", point, sig); // resume(executionId, point.name(), signal)
fixture.cancel("exec-1");             // ACTIVE/SUSPENDED -> CANCELLED
fixture.snapshot("exec-1");           // Optional<DurableSnapshot>，无副作用
fixture.requireSnapshot("exec-1");    // 要求存在，否则 AssertionError
fixture.executable();
```

**崩溃即重抛**：DurableFixture 不吞异常、不自动重试。模拟崩溃时直接捕获命令重抛的异常（如 `DurableException` 的 `STORE_FAILURE`/`REVISION_CONFLICT`），随后重新 compile 并 `recover` 验证从检查点续跑：

```java
DurableFixture<String, String> fixture =
        DurableFixture.withStore(crashStore, flow, "smoke", 1);
try {
    fixture.start("e1", "in");
} catch (RuntimeException crashBetweenCommitAndDrive) {
    // 快照已落库
}
DurableResult<String> recovered =
        DurableFixture.compile(flow, "smoke", 1).recover("e1");
```

---

# 8. ParallelBarrier：并行重叠验证

```java
import com.team4u.framework.flow.test.ParallelBarrier;
```

基于 `CountDownLatch` 的两分支（可扩展 N 分支）屏障，用于验证 Local 并行分支**真并发**——两个分支必须同时进入屏障才能释放，否则测试超时失败而非死锁：

```java
ParallelBarrier barrier = new ParallelBarrier(2);

Branch<String, String> left = Branch.of("left", (context, input) -> {
    barrier.enter();                          // 登记入场后阻塞直到 release
    return Outcome.accepted(input + "-left");
});
Branch<String, String> right = Branch.of("right", (context, input) -> {
    barrier.enter();
    return Outcome.accepted(input + "-right");
});

Flow<String, String> flow = Flow.<String>parallel(left, right)
        .join(results -> results.homogeneousCollect().map(String::valueOf));

FlowResult<String> result = Local.compile(flow).run("in");

org.junit.Assert.assertTrue(barrier.awaitEntered(2000)); // 2s 内两分支均已进入=真重叠
barrier.release();                                       // 放行（幂等）
FlowAssertions.assertCompleted(result);
```

API：`new ParallelBarrier(branches)`、分支侧 `enter()` / `enter(timeout, unit)`（带超时保护）、测试侧 `awaitEntered(timeoutMillis)`（超时返回 false，不死等）、`release()`（幂等放行）。

---

# 9. 推荐测试矩阵

| 验证目标 | 工具组合 |
| :--- | :--- |
| 四态传播与短路 | `OperationStub` 四工厂 + `FlowAssertions.assertAccepted/Rejected/Skipped/Failed` |
| retry 幂等键与次数 | `OperationStub.failing` + `calls()` 中 `invocationId()` 相等断言 + `callCount()` |
| Policy 决策与顺序 | `PolicyStub.proceeding/deciding` + `beforeCalls()/afterCalls()` |
| 挂起与恢复（Local） | `LocalFixture.requireSuspension` + `resume` + `assertSuspended/assertAccepted` |
| Durable 崩溃恢复 | `DurableFixture.withStore`（探针 store）+ `recover` + `snapshot` |
| resume 信号幂等 | `DurableFixture.resume` 同值/异值信号 + `assertActive` |
| 并行真并发与隔离 | `ParallelBarrier` + `TraceCollector.ofType(PARALLEL_BRANCH_COMPLETED)` |
| 事件顺序 | `TraceCollector.types()` / `nodePaths(...)` |
