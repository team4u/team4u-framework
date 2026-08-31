# 核心语义与机制

本章提供 `team4u-flow` 核心语义与运行机制的全景总览。各项专题已细化并独立成章，推荐结合各独立专章深入研读：

- 结果类型体系与代数映射：[四态业务结果与生命周期模型](flow-outcome.md)
- 状态传播与短路规则：[四态传播规则与消费机制](flow-propagation.md)
- 8 种运行时节点详解：[运行时节点与 DSL 编排原语](flow-nodes.md)
- Policy、Retry 与 Timeout：[流程治理：Policy 策略、Retry 重试与 Timeout 控制](flow-governance.md)
- 并行执行与汇合策略：[并行分支与汇合治理](flow-parallel.md)
- 挂起恢复与协作取消：[挂起续接与协作式取消合同](flow-suspend.md)
- 线程模型与死锁防御：[Local 线程模型与死锁防御机制](flow-threading.md)
- 全链路诊断码与排查：[诊断码体系与故障排查手册](flow-diagnostics.md)

---

## 结果类型体系

> [!TIP]
> 专章详述请参阅：[四态业务结果与生命周期模型](flow-outcome.md)

### 结果类型对照

框架包含三套分属于不同层次的结果类型：

| 结果类型 | 所属层次 | 状态闭集 | 携带载荷 |
| :--- | :--- | :--- | :--- |
| `Outcome<T>` | 业务层 | `Accepted` / `Rejected` / `Skipped` / `Failed` | 仅 `Accepted` 携带输出值；其余三态携带 `Reason` 或 `Failure` 诊断信息 |
| `FlowResult<O>` | Local 执行层 | `Completed` / `Suspended` / `Cancelled` | `Completed` 携带最终 `Outcome`；`Suspended` 携带单次消费句柄 `Suspension`；`Cancelled` 仅携带 `executionId` |
| `DurableResult<O>` | Durable 持久化层 | `Completed` / `Suspended` / `Active` / `Cancelled` | `Completed` 携带 `Outcome`；`Suspended` 携带挂起点名称；`Active` 携带 `wakeAt` 唤醒时刻；`Cancelled` 携带快照 |

### 业务四态与执行生命周期分层

框架将“业务结果”与“执行生命周期”严格解耦：
- **业务四态**回答“业务逻辑达成了何种业务结论”；
- **执行生命周期**回答“当前执行处于何种运行状态”。

只有 `Completed` 携带业务 `Outcome`（可能是四态中的任一种）；`Suspended` 与 `Cancelled` 不携带业务输出。调用 `FlowResult.requireAccepted()` 时，要求执行处于 `Completed` 且业务结果为 `Accepted`，否则抛出 `IllegalStateException`。

---

## 四态 Outcome 与传播规则

> [!TIP]
> 专章详述请参阅：[四态传播规则与消费机制](flow-propagation.md)

### 四态定义

`Outcome<T>` 是业务结果的封闭枚举式抽象（模块外不可继承）：

| 状态 | 载荷 | 语义说明 |
| :--- | :--- | :--- |
| `Accepted` | 非 null 输出值 | 成功产出业务数据，**四态中唯一携带输出** |
| `Rejected(Reason)` | 稳定业务码 `Reason` | 业务拒绝（如黑名单、余额不足）；属于正常业务分支，不触发重试与技术恢复 |
| `Skipped(Reason)` | 稳定业务码 `Reason` | 弃权跳过（当前节点不适用）；可被 `thenOptional` 或 `firstApplicable` 消费，或触发降级 |
| `Failed(Failure)` | 稳定失败码 `Failure` | 执行失败（技术异常、超时、外部故障）；可触发 `retry` 重试或 `recoverWith` 补偿 |

`Reason` 与 `Failure` 均为不可变值对象，包含稳定的业务码 `code`、可读说明 `message` 与键值详情 `details`。

```java
Outcome<String> ok = Outcome.accepted("value");
Outcome<String> reject = Outcome.rejected(Reason.of("INSUFFICIENT_BALANCE", "余额不足"));
Outcome<String> skip = Outcome.skipped(Reason.of("NO_APPLICABLE_CHANNEL", "无适用渠道"));
Outcome<String> fail = Outcome.failed(Failure.of("GATEWAY_TIMEOUT", "网关超时"));

// 仅对 Accepted 映射输出，其余三态原样透传
Outcome<Integer> length = ok.map(String::length);
```

### 传播规则总表

| 传播场景 | 行为规则 |
| :--- | :--- |
| **`then` (Sequence)** | **仅 Accepted 推进**：前置节点 Accepted 时其输出作为后置节点输入；Rejected / Skipped / Failed 直接短路终止当前序列 |
| **`thenOptional`** | 仅用于同类型 `O -> O` 节点：Accepted 以新值推进；Skipped 消费弃权并以进入步骤前的原值推进；Rejected / Failed 仍短路 |
| **`Rejected`** | 终止当前 Sequence 并逐层向外透传；不触发 `firstApplicable` 候选推进与 `recoverWith` 补偿 |
| **`Skipped`** | 默认终止当前 Sequence；在 `firstApplicable` 或 `thenOptional` 边界被消费，否则向外透传 |
| **`Failed`** | 终止当前 Sequence；触发同作用域内的 `retry` 重试或 `recoverWith` 恢复边界；否则向外透传 |
| **`firstApplicable`** | 依次尝试各个候选分支，**以首个非 Skipped 结果作为整体结果**；全部 Skipped 则整体 Skipped |
| **`recoverWith`** | 主流程 Failed 时，以 `Recovery<I>`（原始输入 + Failure）作为输入执行恢复流程；非 Failed 原样透传 |
| **`route`** | selector 产出路由键（精确 `equals` 匹配）选中分支；未命中且未配置 `otherwise` 时整体 Skipped（`NO_ROUTE`） |
| **`parallel`** | wait-all 等待全部分支完成后，由 `JoinStrategy` 合并为单个 Outcome |

### Skipped 消费机制

`Skipped` 是四态中唯一可被框架结构内部消费的状态，共有三个标准消费位置：

| 消费位置 | 消费方式 | 外层感知结果 | 典型场景 |
| :--- | :--- | :--- | :--- |
| `thenOptional(next)` | 局部消费：next 返回 Skipped 时，通过 Identity 分支回退至**进入 optional 前的原值**继续推进 | `Accepted(entryValue)` | 属性增强、非必填优惠计算等可选步骤 |
| `firstApplicable(a, b, ...)` | 候选推进：某分支 Skipped 时尝试下一分支；首个非 Skipped 即为整体结果 | 首个非 Skipped 分支的 Outcome | 多渠道降级、多数据源逐级回退 |
| `route().withoutOtherwise()` | 直接透传：未命中任何 `caseOf` 且未声明默认分支 | 整体 `Skipped(NO_ROUTE)` | 显式表达“无匹配规则即业务未决” |

除上述特定消费边界外，`Skipped` 在普通 `then`、`route` 分支内部及 `parallel` 分支中均按默认短路规则向外传播。

### thenOptional 内部机制

`thenOptional` 在 DSL 构建阶段复用了 Fallback 与 Identity 原语：

```java
flow.thenOptional(next);

// 语义等价于：
flow.then(Flow.firstApplicable(next, Flow.identity()));
```

- 当可选节点返回 `Skipped` 时，节点自身正常记录 `NODE_COMPLETED (Skipped)` 事件；
- 外层 Fallback 捕获 Skipped 后选择 Identity 分支，将步骤入口原值转换为 `Accepted(entryValue)`，由后续 Sequence 继续推进；
- **类型契约约束**：`thenOptional` 仅接受 `Operation<O, O>` 或 `Flow<O, O>`，跨类型 `O -> N` 无法在弃权时提供 `N`，调用将在 Java 编译期报错；
- **子流程作用域**：`thenOptional(Flow<O, O>)` 将整个子流程视为一个可选作用域，子流程最终 Skipped 时回退的是进入子流程前的值，不泄漏中间 Accepted 值。

---

## 运行时节点语义

> [!TIP]
> 专章详述请参阅：[运行时节点与 DSL 编排原语](flow-nodes.md)

编译后的运行时计划封闭为八种 `NodeDescriptor.Kind`，不开放自定义节点类型：

### INVOKE (调用节点)
执行绑定的 `Operation`。
- **实例与容器双绑定**：支持传入 `Operation` 实例或 `Class<? extends Operation>`（配合可选限定符），编译期由容器解析为单例引用；
- **use 上下文调用**：`use(operation, project, merge)` 派生临时入参并合并结果，用于调用外部接口而不破坏主流程上下文；
- **异常收敛**：业务抛出的异常被捕获并转换为携带 `OPERATION_EXCEPTION` 诊断码的 `Failed`，返回值若是 `null` 亦会被严格拒绝。

### SEQUENCE (顺序节点)
按声明顺序串联子节点，仅由 `Accepted` 推进。
- 匿名 `then` 会被扁平化合并到同一执行列表中；
- `Flow.scope(name, body)` 创建具名作用域，作为 Fallback 恢复与超时控制的作用域边界。

### ROUTE (路由节点)
执行 selector 获取路由键，按精确 `equals` 匹配分支。
- `caseOf` 支持任意类型的路由键（opaque key）；重复键在构建阶段即抛出 `DUPLICATE_ROUTE_CASE`；
- 配置 `otherwise(branch)` 提供兜底，或使用 `withoutOtherwise()` 在未命中时返回 `Skipped(NO_ROUTE)`。

### FALLBACK (降级与恢复节点)
按触发条件尝试分支序列，仅支持两种触发器：
- **SKIPPED 触发器**：`firstApplicable` 与 `thenOptional`，首个非 Skipped 结果胜出；
- **FAILED 触发器**：`recoverWith`，主分支 Failed 时携带 `Recovery<I>` 进入恢复分支。

### PARALLEL (并行节点)
`Flow.parallel(branches...).join(joinStrategy)` 并发执行多个分支。
- **true wait-all 合同**：全部分支执行完毕后方才进入 `JoinStrategy`；
- **取消不进 join**：执行被取消时绕过 Join 逻辑，直接流向 Cancelled 终点；
- **分支约束**：并行分支内部禁止使用 `await` 与 `PersistentPolicy`（编译期静态拦截）；
- **内置合并策略**：
  - `allAccepted()`：全部分支 Accepted 时返回所有结果，否则短路返回首个非 Accepted；
  - `firstAccepted()`：按声明顺序返回首个 Accepted，若无则返回 `Skipped(NO_APPLICABLE_BRANCH)`；
  - `quorum(n)`：成功分支数达到阈值 $n$ 时 Accepted，否则返回 `Failed(QUORUM_NOT_REACHED)`；
  - `homogeneousCollect()`：全 Accepted 时按声明顺序汇总为列表。

### AWAIT (挂起节点)
`flow.await(ResumePoint.named("pointName"))` 挂起当前流程。
- Local 模式下返回 `FlowResult.Suspended` 与单次消费句柄 `Suspension`；
- 恢复时调用 `executable.resume(suspension, resumePoint, signal)`，输出 `Resumed<State, Signal>`；
- 同一流内挂起点名称必须唯一（`DUPLICATE_RESUME_POINT`）。

### CONTROL (控制节点)
包裹在 Flow 上的治理节点，包含四类控制形态（`ControlKind`）：`POLICY`、`PERSISTENT_POLICY`、`RETRY` 与 `TIMEOUT`。

### COMPLETE (完结节点)
静态常量终点：`Flow.identity()`（原样透传）、`Flow.accepted(v)`、`rejected(r)`、`skipped(r)`、`failed(f)`。

---

## 扩展点接口契约

### Operation 业务步骤

```java
public interface Operation<I, O> {
    Outcome<O> execute(OperationContext context, I input) throws Exception;
}
```

- 同步、可复用、线程安全；避免持有跨调用可变状态；
- `OperationContext` 提供元数据 `metadata()`、稳定幂等键 `invocationId()`、取消信号 `cancellation()` 以及 `await(CompletionStage)` 异步转同步支持。

### Policy 无状态网关

```java
public interface Policy<K> {
    Gate before(PolicyContext context, K key);
    default void after(PolicyContext context, K key, Completion completion) { }
}
```

- `before` 返回 `Gate.proceed()`、`Gate.reject(Reason)` 或 `Gate.fail(Failure)`；
- `after` 在主体执行后回调，接收四态完成摘要 `Completion`。

### PersistentPolicy 持久化控制策略

```java
public interface PersistentPolicy<K, S> {
    S initialState(K key);
    Before<S> before(PolicyContext context, K key, S state);
    After<S> after(PolicyContext context, K key, S state, Completion completion);
}
```

- 不可变状态 `S` 由框架持久化至快照中；
- `before` 支持 `Proceed`、`WaitUntil`、`Reject`、`Fail`；`after` 支持 `Return`、`RetryAt`。

### JoinStrategy 并行汇合

```java
public interface JoinStrategy<O> {
    Outcome<O> join(ParallelResults results);
}
```

接收声明顺序的分支结果集合，合并为单个业务 `Outcome`。

---

## 治理控制机制

> [!TIP]
> 专章详述请参阅：[流程治理：Policy 策略、Retry 重试与 Timeout 控制](flow-governance.md)

### 治理策略体系与架构对称性

- **无状态治理（`Policy<K>`）**：如 `team4u-flow-ratelimiter`，提供基于纯内存或分布式缓存的快速准入裁决（`proceed` / `reject` / `fail`）；
- **有状态治理（`PersistentPolicy<K, S>`）**：如 `team4u-flow-retry`，通过不可变状态变迁承载重试尝试、退避调度（`retryAt`）与崩溃恢复；
- **时效控制（`Timeout`）**：`flow.timeout(Duration)` 为当前作用域设置超时时限，超时后产生 `TIMEOUT` 失败并终止当前作用域。

### 重试策略（`team4u-flow-retry`）

```java
FlowRetryPolicy<OrderRequest> policy = FlowRetries.exponential(3, 100, 2.0, 1000);
Flow<OrderRequest, Receipt> retried = policy.wrap(Flow.step(chargeOperation), Function.identity());
```

- `maxAttempts` 包含首次执行；在 Failed 时按 backoff 间隔重试，直到成功或次数耗尽；
- 重试过程中 `invocationId`（`flowId:flowVersion:executionId:path`）保持稳定，便于下游做幂等去重。

---

## Local 执行驱动与线程模型

> [!TIP]
> 专章详述请参阅：[Local 线程模型与死锁防御机制](flow-threading.md)

### 执行入口 API

| API 方法 | 签名要点 | 语义说明 |
| :--- | :--- | :--- |
| **`run`** | `run(I input[, Cancellation c])` | 在调用方线程同步执行 `SerialMachine`，返回 `FlowResult<O>` |
| **`runAsync`** | `runAsync(I input[, Cancellation c][, ExecutorService d])` | 提交至 Dispatcher 线程池异步执行，返回 `CompletionStage<FlowResult<O>>` |
| **`resume`** | `resume(Suspension s, ResumePoint p, R signal[, Cancellation c])` | 在当前线程恢复挂起的执行，单次消费句柄 |
| **`resumeAsync`** | `resumeAsync(Suspension s, ResumePoint p, R signal, ...)` | 在 Dispatcher 线程池中异步恢复执行 |
| **`withExecutor`** | `withExecutor(ExecutorService workerExecutor)` | 派生绑定新 Worker 线程池的 `LocalExecutable` |

### 线程模型：Dispatcher 与 Worker

- **Dispatcher 调度线程池**：负责 `runAsync` / `resumeAsync` 的顶层发起调度；
- **Worker 工作线程池**：负责 `parallel` 分支并发执行与超时监控，默认使用 `ForkJoinPool.commonPool()`。

### 线程池死锁防御规则

为了防止嵌套任务在有限线程池中发生**线程饥饿死锁 (Thread Starvation Deadlock)**，框架在编译与构建期提供两级静态防御（违规时抛出 `IllegalArgumentException`）：

- **Dispatcher 与 Worker 隔离校验**：含 `parallel` 或 `timeout` 的流程，严禁将**同一个非 ForkJoinPool** 的单线程或有界线程池同时用作 Dispatcher 与 Worker；
- **嵌套并行补偿校验**：`parallel` 分支内部还嵌套 `parallel` 或 `timeout` 时，Worker 必须是支持工作窃取与线程补偿的 `ForkJoinPool`。

---

## 协作式取消与 wait-all 合同

> [!TIP]
> 专章详述请参阅：[挂起续接与协作式取消合同](flow-suspend.md) 与 [并行分支与汇合治理](flow-parallel.md)

`Cancellation` 是协作式取消令牌：
- `cancel()` 通过 CAS 标记取消，并向绑定的运行线程发送中断信号；
- 子令牌（`Cancellation.linked(parent)`）级联响应父令牌取消；
- **Parallel wait-all 保证**：并行块取消时，框架将等待所有已启动分支的工作线程完全退出后方才返回，绝不泄漏后台悬挂线程；
- 被取消的分支不进入 `JoinStrategy`，整体流程直接流向 `Cancelled` 终态。

---

## 异常收敛与诊断码体系

> [!TIP]
> 专章详述请参阅：[诊断码体系与故障排查手册](flow-diagnostics.md)

框架将所有异常统一收敛至 `FlowDiagnosticCodes` 诊断码，不向外逃逸未受检异常。

### 运行时失败码与弃权码

**Failed 失败码**：

| 稳定码 | 触发原因 |
| :--- | :--- |
| `OPERATION_EXCEPTION` | `Operation` 执行中抛出未受检异常、业务异常或返回 null |
| `OPERATION_INTERRUPTED` | 操作执行线程被物理中断 |
| `OPERATION_CANCELLED` | 操作在执行中检测到取消信号 |
| `TIMEOUT` | 作用域执行耗时超出 `timeout` 时限 |
| `EXECUTOR_REJECTED` | 底层线程池拒绝任务 (`RejectedExecutionException`) |
| `WAIT_INTERRUPTED` | 策略在退避延时等待中被中断 |
| `POLICY_EXCEPTION` | Policy 回调执行发生异常 |
| `JOIN_EXCEPTION` | `JoinStrategy` 合并逻辑抛出异常 |
| `PARALLEL_EXCEPTION` | Parallel 分支线程以异常结束 |
| `PARALLEL_INTERRUPTED` | Parallel 等待线程被中断 |
| `QUORUM_NOT_REACHED` | `quorum(n)` 汇聚时成功分支数不足 |

**Skipped 弃权码**：

| 稳定码 | 触发原因 |
| :--- | :--- |
| `NO_ROUTE` | 路由未命中任何 `caseOf` 且未提供 `otherwise` |
| `NO_APPLICABLE_BRANCH` | `firstAccepted()` 汇聚时无任何分支 Accepted |

### 编译期静态校验码 (FlowBuildException)

在编译阶段静态校验流程结构，若存在违规项则聚合抛出 `FlowBuildException`：

| 诊断码 | 校验类型 | 违规原因 |
| :--- | :--- | :--- |
| `DUPLICATE_LABEL` | 节点标识 | 同一节点叠加了多个 `named` 标签 |
| `DUPLICATE_SCOPE` | 作用域 | 存在重名的具名作用域 (`scope`) |
| `DUPLICATE_BRANCH` | 并行分支 | `parallel` 中存在同名分支 (`Branch.name`) |
| `DUPLICATE_RESUME_POINT` | 挂起点 | 存在重名的挂起点 (`ResumePoint.named`) |
| `PARALLEL_AWAIT` | 非法挂起 | 在 `parallel` 分支内部使用了 `await` |
| `PARALLEL_PERSISTENT_POLICY` | 非法策略 | 在 `parallel` 分支内部使用了 `persistentPolicy` |
| `INVALID_BINDING` | 契约违规 | 绑定的类未实现预期的扩展点接口 |
| `MISSING_BINDING` | 依赖缺失 | 容器中未找到声明的 Bean（类型或限定符不匹配） |
| `BINDING_TYPE` | 类型不匹配 | 解析出的 Bean 实例与声明契约类型不一致 |
| `DUPLICATE_ROUTE_CASE` | 路由键 | `route` 中声明了重复的 case 键（调用时即时抛出） |

---

## 流程观测 (FlowObserver)

`FlowObserver` 支持在 Local 与 Durable 执行过程中监听生命周期事件：

| 分类 | 事件类型 |
| :--- | :--- |
| **流程生命周期** | `FLOW_STARTED`, `FLOW_COMPLETED`, `FLOW_SUSPENDED`, `FLOW_CANCELLED` |
| **节点生命周期** | `NODE_STARTED`, `NODE_COMPLETED` |
| **路由与降级** | `ROUTE_SELECTED`, `FALLBACK_SELECTED` |
| **策略评估** | `POLICY_BEFORE`, `POLICY_AFTER`, `POLICY_WAITING` |
| **并行汇合** | `PARALLEL_STARTED`, `PARALLEL_BRANCH_COMPLETED`, `PARALLEL_JOINED` |

---

## 下一步

- 掌握 Spring 容器绑定与切面代理：[Bean 容器集成](flow-bean.md)
- 了解 CAS 检查点与崩溃恢复机制：[Durable 持久化执行](flow-durable.md)
- 学习流程图渲染与测试断言：[可视化与图表渲染](flow-graph.md) / [测试支持与断言](flow-test.md)
