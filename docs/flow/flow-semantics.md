# 核心语义与机制

本章说明 `team4u-flow` 的四态结果模型、传播规则、生命周期分层、八节点语义、控制机制与取消合同。

---

# 1. 四态 Outcome 与传播规则

## 1.1 四态定义

`Outcome<T>` 是业务结果的严格四态闭集（Java 8 封闭设计，模块外不可实现或继承）：

| 状态 | 载荷 | 语义 |
| :--- | :--- | :--- |
| `Accepted` | 非 null 输出值 | 成功产出；**四态中唯一携带输出** |
| `Rejected(Reason)` | 稳定业务码 reason | 业务拒绝（如额度不足、黑名单）；正常业务分支，不进入失败恢复 |
| `Skipped(Reason)` | 稳定业务码 reason | 弃权/跳过（无适用分支）；可被 `firstApplicable` 消费，或触发降级 |
| `Failed(Failure)` | 稳定失败码 failure | 执行失败（技术异常、超时、外部系统故障）；触发 `recoverWith` 或 `retry` |

`Reason` 与 `Failure` 均为不可变值对象：`code`（稳定业务/失败码）、`message`（可读说明）、`details`（不可变键值补充），三者不可为 null 或空白。

```java
Outcome<String> ok = Outcome.accepted("value");
Outcome<String> no = Outcome.rejected(Reason.of("INSUFFICIENT_BALANCE", "余额不足"));
Outcome<String> skip = Outcome.skipped(Reason.of("NO_APPLICABLE_CHANNEL", "无适用渠道"));
Outcome<String> bad = Outcome.failed(Failure.of("GATEWAY_TIMEOUT", "网关超时"));
```

仅对 Accepted 的输出应用映射、其余三态原样透传类型参数：

```java
Outcome<Integer> length = ok.map(String::length);
```

## 1.2 传播规则

| 传播场景 | 规则 |
| :--- | :--- |
| `then`（Sequence） | **仅 Accepted 推进**：前节点 Accepted 时其输出作为后节点输入；Rejected/Skipped/Failed 直接短路为该 Sequence 的最终 Outcome，后续节点不执行 |
| `Rejected` | 终止当前 Sequence，向外层逐层透传；不触发 `firstApplicable` 与 `recoverWith` |
| `Skipped` | 终止当前 Sequence；在 `firstApplicable` 容器内被消费（尝试下一分支）；否则向外透传 |
| `Failed` | 终止当前 Sequence；触发同 scope 的 `recoverWith` 恢复边界或 `retry` 控制重试；否则向外透传 |
| `firstApplicable` | 依次尝试各分支，**首个非 Skipped 的 Outcome 即为整体结果**；全部 Skipped 则整体 Skipped |
| `recoverWith` | 当前 Flow Failed 时，以 `Recovery<I>`（原始 scope 输入 + 最终 Failure）作为恢复 Flow 的输入重新执行；非 Failed 原样透传 |
| `route` | selector 的输出作为路由键（精确 `equals` 匹配）选中分支，分支 Outcome 即整体结果；无匹配且无 `otherwise` 时整体 Skipped |
| `parallel` | wait-all 等待全部分支完成后交给 `JoinStrategy` 合并为单个 Outcome |

```java
// Rejected 短路：第二个 Operation 不会执行
Flow<String, Integer> flow = Flow.<String, Integer>rejected(
                Reason.of("BLACKLISTED", "命中黑名单"))
        .then((context, value) -> Outcome.accepted(value.length())); // 不会到达
```

## 1.3 生命周期分层

框架把"业务结果"与"执行生命周期"严格分成两层：

```text
Operation 返回          Outcome<T>（四态，业务层）
        │
FlowResult<O>（Local 执行层，三态）
├── Completed(outcome)     正常结束，携带最终 Outcome（可能是四态中任一种）
├── Suspended(suspension)  执行挂起在某个 ResumePoint，携带单次消费的续接句柄
└── Cancelled(executionId) 执行被取消，仅保留 executionId
```

- 业务四态回答"业务走到了哪个结果"；执行三态回答"这次执行本身处于什么状态"。
- 只有 `Completed` 才携带 Outcome；`Suspended` 与 `Cancelled` 不携带业务结果，但 Durable 场景下挂起前的 scope entry 会保留在快照中以便恢复。
- `FlowResult.requireAccepted()` 便捷方法要求 Completed/Accepted，否则抛 `IllegalStateException`。

---

# 2. 八节点运行时语义

编译后的运行时计划封闭为八种 `NodeDescriptor.Kind`，不提供自定义节点：

## 2.1 INVOKE（调用）

执行绑定的 `Operation`。`Flow.step` / `then` / `thenOptional` / `use` 均编译为 INVOKE 节点。

- **支持实例与 Bean 双绑定**：可以直接传入 `Operation` 实例，也可以传入 `Class<? extends Operation>` 与可选限定符 `qualifier`（Spring Bean 名称），在编译期由容器解析为单例 Bean（Bean 是一等公民）。
- **use 上下文调用**：通过 `project` 从当前输出派生入参、`merge` 合并原输出与 Operation 输出，用于"调用外部服务但不丢失主上下文"的场景（同样支持 Class/Qualifier 绑定）。
- **异常收敛**：异常被捕获并转换为 `OPERATION_EXCEPTION` 稳定 Failed（见第 6 节）。

## 2.2 SEQUENCE（顺序）

按声明顺序执行子节点，仅 Accepted 推进。相邻匿名 `then` 会被扁平化为同一子节点列表；`Flow.scope(name, body)` 创建具名 Sequence，是 Fallback 恢复边界与超时作用域的载体。

## 2.3 ROUTE（路由）

先执行 selector Operation（支持 Lambda、实例或 `Class`/`qualifier` 容器 Bean）得到路由键，再按键选中分支：

- 键匹配为精确 `equals`；重复 case 在构建期以 `DUPLICATE_ROUTE_CASE` 拒绝。
- 声明 `otherwise` 时未匹配走默认分支；声明 `withoutOtherwise` 时未匹配整体 Skipped（`NO MATCH` 语义）。
- 路由键对框架是不透明值（opaque）：`caseOf` 接受任意类型键，图渲染等结构面只呈现可稳定序列化的形态（见 [可视化文档](flow-graph.md)）。

## 2.4 FALLBACK（降级/恢复）

按触发器尝试分支序列：

- `Flow.firstApplicable(flowA, flowB, ...)`：Trigger 为 SKIPPED，依次尝试，首个非 Skipped 即结果。
- `flow.recoverWith(recoveryFlow)`：Trigger 为 FAILED，主分支 Failed 时以 `Recovery<I>`（`input()` 原始 scope 输入 + `failure()` 最终 Failure）作为恢复分支输入。
- 恢复分支自身 Failed 时按外层规则继续传播，不会无限递归。

## 2.5 PARALLEL（并行）

`Flow.parallel(Branch.of(name, flowA), Branch.of(name2, flowB)).join(strategy)`：

- **wait-all 合同**：等待全部分支业务完成（含 Rejected/Skipped/Failed）后才交给 `JoinStrategy`；分支名在构建期校验唯一（`DUPLICATE_BRANCH`）。
- **取消不进 join**：执行被取消时分支与整体直接走 CANCELLED 出口，JoinStrategy 不会被调用（见第 5 节）。
- **分支限制**：分支内不能 `await`（构建期 `PARALLEL_AWAIT` 拒绝）、不能使用 `PersistentPolicy`（`PARALLEL_PERSISTENT_POLICY` 拒绝）。
- Local 执行器分支由 worker 线程池真并发执行；Durable 执行器按声明顺序串行驱动分支（崩溃一致性合同允许的简化，见 [Durable 文档](flow-durable.md)）。
- 内置合并策略（`ParallelResults` 上的方法，配合 `join` 使用）：`allAccepted()`（全 Accepted 才 Accepted，否则首个非 Accepted）、`firstAccepted()`（首个 Accepted，无则 Skipped）、`quorum(n)`（达到法定数才 Accepted，否则 `QUORUM_NOT_REACHED` Failed）、`homogeneousCollect()`（同质收集为列表）。

```java
Branch<String, Integer> length = Branch.of("length",
        (context, value) -> Outcome.accepted(value.length()));
Branch<String, Integer> upper = Branch.of("upper",
        (context, value) -> Outcome.accepted(value.toUpperCase()));

Flow<String, String> flow = Flow.<String>parallel(length, upper)
        .join(results -> results.homogeneousCollect().map(String::valueOf));
```

## 2.6 AWAIT（挂起）

`flow.await(ResumePoint.named("approval"))` 在当前点挂起：

- Local：`run` 返回 `FlowResult.Suspended`，携带 `Suspension`（不透明、单次消费、仅可由产生它的 `LocalExecutable` 恢复）。
- 恢复：`executable.resume(suspension, point, signal)`，信号类型由 `ResumePoint<R>` 静态约束；恢复后输出 `Resumed<S, R>`（`state()` 挂起前 scope entry + `signal()` 注入信号）。
- `ResumePoint` 的 name 在同一 Flow 内唯一（构建期拒绝重复）；resume 时 name 不匹配会以 `RESUME_POINT_MISMATCH` 类错误拒绝。
- Suspension 二次消费抛 `IllegalStateException`。

```java
ResumePoint<String> approval = ResumePoint.named("approval");
Flow<Integer, String> flow = Flow.<Integer>identity()
        .await(approval)
        .then((context, resumed) -> Outcome.accepted(
                resumed.state() + ":" + resumed.signal()));

LocalExecutable<Integer, String> executable = Local.compile(flow);
FlowResult.Suspended<String> suspended =
        (FlowResult.Suspended<String>) executable.run(42);
String output = executable
        .resume(suspended.suspension(), approval, "yes")
        .requireAccepted(); // "42:yes"
```

## 2.7 CONTROL（控制）

包裹当前 Flow 的控制节点，四种 `ControlKind`：`POLICY`、`PERSISTENT_POLICY`、`RETRY`、`TIMEOUT`（见第 4 节）。

## 2.8 COMPLETE（完结）

静态终点：`Flow.identity()`（透传输入）、`Flow.accepted(v)` / `rejected(r)` / `skipped(r)` / `failed(f)`（固定 Outcome）。

---

# 3. 扩展点合同

## 3.1 Operation

```java
public interface Operation<I, O> {
    Outcome<O> execute(OperationContext context, I input) throws Exception;
}
```

- 同步、可复用、线程安全；实现应避免持有跨调用可变状态。
- `OperationContext` 只暴露 `metadata()`、稳定幂等键 `invocationId()`、`cancellation()` 取消信号，以及把 `CompletionStage` 同步阻塞为值的 `await(stage)`（取消时抛 `CancellationException`）。
- 返回 null 会被严格拒绝；抛出的异常统一转稳定 Failed（第 6 节）。

## 3.2 Policy（无状态网关）

```java
public interface Policy<K> {
    Gate before(PolicyContext context, K key);
    default void after(PolicyContext context, K key, Completion completion) { }
}
```

- `before` 返回闭集 `Gate`：`Gate.proceed()`（放行）、`Gate.reject(Reason)`（终出 Rejected）、`Gate.fail(Failure)`（终出 Failed）。
- `after` 在主体完成后回调，`Completion` 是不携带输出值的四态摘要（kind + 可选 reason/failure）。
- `keyProjection`（`flow.policy(policy, keyFn)`）从输入派生策略键，用于限流、配额等以键为粒度的网关。
- 无框架管理状态；需要跨重启状态请用 `PersistentPolicy`。

## 3.3 PersistentPolicy（持久化控制策略）

```java
public interface PersistentPolicy<K, S> {
    S initialState(K key);
    Before<S> before(PolicyContext context, K key, S state);
    After<S> after(PolicyContext context, K key, S state, Completion completion);
}
```

- before 闭集：`Proceed(state)`（放行）、`WaitUntil(instant, state)`（等待到指定时刻再评估）、`Reject(reason, state)`、`Fail(failure, state)`。
- after 闭集：`Return(state)`（立即返回）、`RetryAt(instant, state)`（定时重试）。
- 不可变状态 `S` 由框架持久化在 Durable 快照中（Local 下驻留内存）；状态必须可被 `StateMapper` 确定性编码。
- 不能用于 Parallel 分支（构建期拒绝）。
- 静态工厂：`PersistentPolicy.proceed/waitUntil/reject/fail` 与 `returning/retryAt`。

## 3.4 JoinStrategy

```java
public interface JoinStrategy<O> {
    Outcome<O> join(ParallelResults results);
}
```

接收声明顺序的分支结果集合，返回单个 Outcome。`ParallelResults` 提供按 token 的类型化查找 `outcome(branch)` 与内置合并策略（`allAccepted/firstAccepted/quorum/homogeneousCollect`），`Values` 仅包含 Accepted 分支的输出并支持 `get(branch)`/`contains(branch)`。

## 3.5 组件绑定与 Bean 一等公民模型 (Binding & Resolution Model)

`team4u-flow` 的所有扩展点（`Operation`、`Policy`、`PersistentPolicy`）均支持两种绑定形态：
1. **显式实例绑定**：`Flow.step(opInstance)`，适合纯函数/测试桩/内联 Lambda；
2. **声明式 Class / Bean 绑定**：`Flow.step(OpClass.class)`、`Flow.step(OpClass.class, "beanQualifier")`，适合 Spring / IoC 生产环境。

### 核心语义与保证：
- **编译期一次性解析**：在 `Local.compile` / `BeanFlows.compile` / `DurableRuntime.compile` 阶段由 `OperationResolver` 完成解析并缓存单例引用，运行期 `run` 时为直接方法调用，零反射损耗。
- **代理原样保留**：Spring 的 `@Transactional` 事务拦截器、AOP 切面及 CGLIB/JDK 代理对象在解析后原样保留并执行，确保事务切面正常生效。
- **结构与执行解耦**：Flow 定义（AST）只记录 `Class` 与 `qualifier` 契约，不持有物理容器对象，天然支持跨环境复用与图表渲染。
- **严格诊断**：若 Bean 缺失或类型不匹配，在编译阶段统一抛出 `FlowBuildException`（内含 `MISSING_BINDING` / `BINDING_TYPE` 明确错误），杜绝运行期隐式故障。
- 完整用法与 Spring 最佳实践详见 [Spring / Bean 容器集成](flow-bean.md)。

---

# 4. 控制机制

## 4.1 retry

```java
Flow<String, String> flaky = Flow.step(riskyOperation)
        .retry(Retry.maxAttempts(3));                       // 无间隔
Flow<String, String> backed = Flow.step(riskyOperation)
        .retry(new Retry(3, Duration.ofSeconds(2)));         // 固定 2s backoff
```

- `maxAttempts` 含首次执行；Failed 时按 backoff 间隔重新执行 body。
- 重试沿用同一 scope entry 且 `invocationId` 保持稳定（`flowId:flowVersion:executionId:path` 不变），外部副作用可据此幂等。
- Local 中 backoff 大于零时执行挂起为等待态（受超时与取消约束）；Durable 中落 ACTIVE+wakeAt 快照，返回 `DurableResult.Active`，由外部调度在 wakeAt 后 `recover` 唤醒。

## 4.2 timeout

```java
Flow<String, String> guarded = flow.timeout(Duration.ofSeconds(5));
```

- 为当前作用域设置截止时间；超时产生 `TIMEOUT` 稳定 Failed 并终止最近的作用域（scope）。
- Duration 必须为正，否则构建期 `IllegalArgumentException`。
- 作用于 `Flow.scope(name, body)` 边界或最近的组合作用域。

## 4.3 policy / persistentPolicy 挂载顺序

`.policy(...)` / `.persistentPolicy(...)` / `.retry(...)` / `.timeout(...)` 均包裹在"当前 Flow"之上，链式调用自外向内生效。例如 `flow.policy(p, k).retry(r)` 表示 policy 先评估、其保护体内才应用 retry；`flow.retry(r).policy(p, k)` 则每次重试都会重新评估 policy。

---

# 5. 取消合同：true wait-all

`Cancellation` 是协作式取消令牌：`cancel()` CAS 置位、中断当前绑定的运行线程，并向子令牌级联传播（Parallel 各分支持父子链接令牌）。

**true wait-all 退出保证**：Parallel 汇合采用"真正阻塞等待所有已启动分支的工作线程完全退出、任何情况下不允许虚假返回"的合同：

- 全部分支业务完成后才进入 `JoinStrategy`；取消发生时取消整组分支并等待它们全部退出。
- **忽略中断会延迟返回**：Operation 若捕获 `InterruptedException` 后既不恢复中断标志也不尽快返回，会让 wait-all 的退出等待被拖长，但框架仍会等到线程真正退出后才返回，不会泄漏分支线程。
- 分支被取消时其结果不会进入 `JoinStrategy`——取消出口绕过 wait-all/join，直接使整体走 CANCELLED 生命周期（图渲染中该合同体现为"CANCELLED 不进 join"，见 [可视化文档](flow-graph.md)）。
- Operation 内部可通过 `context.cancellation().isCancelled()` 主动检查，或用 `context.await(stage)` 让阻塞等待响应取消。

```java
Cancellation cancellation = Cancellation.create();
FlowResult<String> result = Local.compile(flow).run(input, cancellation);
// 其他线程：cancellation.cancel();
// result 为 FlowResult.Cancelled，仅携带 executionId
```

---

# 6. 异常转稳定 Failed

Operation、selector、JoinStrategy、Policy 回调中抛出的异常不会逃逸到调用方，而是被转换为携带稳定失败码的 `Failed` Outcome，随四态规则正常传播（可被 retry / recoverWith 消费）：

| 来源 | 稳定失败码（`FlowDiagnosticCodes`） |
| :--- | :--- |
| 操作执行抛出未受检异常 | `FlowDiagnosticCodes.OPERATION_EXCEPTION` |
| 操作执行线程被中断 | `FlowDiagnosticCodes.OPERATION_INTERRUPTED` |
| 操作被显式取消 | `FlowDiagnosticCodes.OPERATION_CANCELLED` |
| 作用域截止时间到期 | `FlowDiagnosticCodes.TIMEOUT` |
| 线程池拒绝执行任务 | `FlowDiagnosticCodes.EXECUTOR_REJECTED` |
| 路由条件未匹配且无 default 分支 | `FlowDiagnosticCodes.NO_ROUTE` |
| 策略退避等待时被中断 | `FlowDiagnosticCodes.WAIT_INTERRUPTED` |
| 策略回调执行抛出异常 | `FlowDiagnosticCodes.POLICY_EXCEPTION` |
| 并行分支合并（Join）异常 | `FlowDiagnosticCodes.JOIN_EXCEPTION` |

框架在 [`FlowDiagnosticCodes`](file:///root/code/team4u-framework/modules/flow/core/src/main/java/com/team4u/framework/flow/FlowDiagnosticCodes.java) 中提供了标准常量定义，业务与监控告警建议统一引用该常量类进行断言与分流处理。

这意味着：

- 调用方拿到的 `FlowResult` 永远是三态闭集之一，不会是裸异常栈；
- 告警与幂等去重可以基于稳定失败码而非异常类名字符串；
- Java `Error` 不做业务转换，按 JVM 语义向上传播。

```java
OperationStub<String, String> broken = OperationStub.throwing(
        () -> new java.io.IOException("gateway down"));
FlowResult<String> result = Local.compile(Flow.step(broken)).run("in");
// result 为 Completed[Failed[Failure[code=FlowDiagnosticCodes.OPERATION_EXCEPTION, ...]]]
```

---

# 7. 观测

`FlowObserver` 在 Local 与 Durable 执行中通用，事件类型覆盖流程生命周期（`FLOW_STARTED/COMPLETED/SUSPENDED/CANCELLED`）、节点（`NODE_STARTED/COMPLETED`）、路由与降级选择、Policy 评估、并行汇合等；回调抛出的运行时异常被框架隔离，不影响执行。`FlowObserver.composite(...)` 顺序广播，单观察者异常被吞掉。

test 模块的 `TraceCollector` 是线程安全的开箱收集器（见 [测试文档](flow-test.md)）。
