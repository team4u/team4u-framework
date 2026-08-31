# 核心语义与机制

> 层级：L1 + L2 进阶 · 前置：quick-start · 模块：team4u-flow

本章是 `team4u-flow` 的语义参考：四态结果与传播、八节点、扩展点、控制机制、执行驱动与死锁防御、取消、诊断码与观测。L1 在前，L2 深水区在后，各大节标注层级。

---

# 1. 三套结果一张表【L1】

## 1.1 Outcome / FlowResult / DurableResult 对照

框架有三套结果类型，分属不同层次，切勿混用：

| 结果类型 | 所属层 | 状态闭集 | 携带物 |
| :--- | :--- | :--- | :--- |
| `Outcome<T>` | L1 业务层 | Accepted / Rejected / Skipped / Failed | 仅 Accepted 携带输出值；其余三态携带 `Reason`/`Failure` |
| `FlowResult<O>` | L1/L2 执行层（Local） | Completed / Suspended / Cancelled | Completed 携带最终 Outcome；Suspended 携带单次消费的 `Suspension`；Cancelled 仅携带 `executionId` |
| `DurableResult<O>` | L3 持久化执行层 | Completed / Suspended / Active / Cancelled | Completed 携带 Outcome；Suspended 携带 `resumePoint` 名称；Active 携带 `wakeAt` 唤醒时刻 |

前两套是本文主角：`Outcome` 是每个 Operation 的返回值，`FlowResult` 是 `Local.compile(flow).run(input)` 的返回值。`DurableResult` 属于独立的持久化执行器组件 `team4u-flow-durable`（同一份 Flow 定义，换一个执行器，获得崩溃恢复能力），本篇只给指针，详见 [Durable 文档](flow-durable.md)。

## 1.2 业务四态与执行三态的分层

框架把"业务结果"与"执行生命周期"严格分成两层：业务四态回答"业务走到了哪个结果"；执行三态回答"这次执行本身处于什么状态"。只有 `Completed` 携带 Outcome（可能是四态中任一种）；`Suspended` / `Cancelled` 不携带业务结果（Durable 场景下挂起前的 scope entry 保留在快照中以便恢复）。`FlowResult.requireAccepted()` 要求 Completed 且 Accepted，否则抛 `IllegalStateException`。

---

# 2. 四态 Outcome 与传播规则【L1】

## 2.1 四态定义

`Outcome<T>` 是业务结果的严格四态闭集（Java 8 封闭设计，模块外不可实现或继承）：

| 状态 | 载荷 | 语义 |
| :--- | :--- | :--- |
| `Accepted` | 非 null 输出值 | 成功产出；**四态中唯一携带输出** |
| `Rejected(Reason)` | 稳定业务码 reason | 业务拒绝（如额度不足、黑名单）；正常业务分支，不进入失败恢复 |
| `Skipped(Reason)` | 稳定业务码 reason | 弃权/跳过（无适用分支）；可被消费（见 2.3），或触发降级 |
| `Failed(Failure)` | 稳定失败码 failure | 执行失败（技术异常、超时、外部系统故障）；触发 `recoverWith` 或 `retry` |

`Reason` 与 `Failure` 均为不可变值对象：`code`（稳定业务/失败码）、`message`（可读说明）、`details`（保持插入顺序且不可修改的键值补充），三者不可为 null 或空白。

```java
Outcome<String> ok = Outcome.accepted("value");
Outcome<String> no = Outcome.rejected(Reason.of("INSUFFICIENT_BALANCE", "余额不足"));
Outcome<String> skip = Outcome.skipped(Reason.of("NO_APPLICABLE_CHANNEL", "无适用渠道"));
Outcome<String> bad = Outcome.failed(Failure.of("GATEWAY_TIMEOUT", "网关超时"));

// 仅对 Accepted 的输出应用映射，其余三态原样透传类型参数
Outcome<Integer> length = ok.map(String::length);
```

## 2.2 传播规则总表

| 传播场景 | 规则 |
| :--- | :--- |
| `then`（Sequence） | **仅 Accepted 推进**：前节点 Accepted 时其输出作为后节点输入；Rejected/Skipped/Failed 直接短路为该 Sequence 的最终 Outcome，后续节点不执行 |
| `thenOptional` | 仅用于同类型 `O -> O` 节点：Accepted 以新值推进；Skipped 在该局部边界被消费，并以节点入口值推进；Rejected/Failed 仍短路（见 2.3/2.4） |
| `Rejected` | 终止当前 Sequence，向外层逐层透传；不触发 `firstApplicable` 与 `recoverWith` |
| `Skipped` | 默认终止当前 Sequence；在 `firstApplicable` 或 `thenOptional` 生成的 SKIPPED Fallback 内被消费，否则向外透传 |
| `Failed` | 终止当前 Sequence；触发同 scope 的 `recoverWith` 恢复边界或 `retry` 控制重试；否则向外透传 |
| `firstApplicable` | 依次尝试各分支，**首个非 Skipped 的 Outcome 即为整体结果**；全部 Skipped 则整体 Skipped |
| `recoverWith` | 当前 Flow Failed 时，以 `Recovery<I>`（原始 scope 输入 + 最终 Failure）作为恢复 Flow 的输入重新执行；非 Failed 原样透传 |
| `route` | selector 的输出作为路由键（精确 `equals` 匹配）选中分支，分支 Outcome 即整体结果；无匹配且无 `otherwise` 时整体 Skipped（`NO_ROUTE`） |
| `parallel` | wait-all 等待全部分支完成后交给 `JoinStrategy` 合并为单个 Outcome |

```java
// Skipped 被 thenOptional 局部处理：normalize 不适用时，validate 收到原始输入
Flow<Order, Order> optional = Flow.<Order>identity()
        .thenOptional(normalize)
        .then(validate);
```

## 2.3 Skipped 的三个消费位置

Skipped 是四态中唯一"可以被框架结构消费掉"的状态——消费发生后，外层看到的不再是 Skipped。共有三个消费位置：

| 消费位置 | 消费方式 | 消费后外层看到 | 典型用途 |
| :--- | :--- | :--- | :--- |
| `thenOptional(next)` | 局部消费：next 返回 Skipped 时，SKIPPED Fallback 选择 Identity 分支，以 **optional scope 的入口值** 产出 Accepted 继续推进 | `Accepted(entry)`——保留进入 optional 前的原值 | 归一化、增强类步骤"不适用就跳过" |
| `firstApplicable(a, b, ...)` | 候选推进：某分支 Skipped 时尝试下一分支；**首个非 Skipped** 即整体结果 | 首个非 Skipped 分支的 Outcome（可为 Accepted/Rejected/Failed）；全部 Skipped 则整体 Skipped | 多候选逐个尝试（渠道路由、多源取数） |
| `route().withoutOtherwise()` | 不消费、直接定性：selector 键未命中任何 caseOf 且无默认分支 | 整体 `Skipped(NO_ROUTE)`——Skipped 作为流程级"未决"结果透传出去 | 显式声明"无匹配即是业务答案" |

三者的本质区别：`thenOptional` 把 Skipped **转成** Accepted（值回退到入口）；`firstApplicable` 把 Skipped 当作"**换下一个**"的信号；`withoutOtherwise` 不做任何转换，让 Skipped **成为最终答案**。除此之外的一切位置（普通 `then`、`route` 分支内部、`parallel` 分支），Skipped 都按 2.2 的默认规则短路外传。

## 2.4 thenOptional：局部消费 Skipped 的内部机制

`thenOptional` 不修改四态模型，也不改变普通 Sequence 的"仅 Accepted 推进"规则。它在 DSL 构建期复用现有 Fallback 与 Identity 原语：

```java
flow.thenOptional(next);

// 语义等价于：
flow.then(Flow.firstApplicable(next, Flow.identity()));
```

因此，可选节点返回 Skipped 时会发生两层结果：节点自身真实完成为 Skipped（`NODE_COMPLETED` 事件保留该状态与 Reason）；外层 SKIPPED Fallback 选择 identity 分支，把 optional scope 的入口值转换为 Accepted，再由普通 Sequence 推进。

这意味着 Skipped 没有被改造成携值状态，也不需要业务节点用 `Accepted(input)` 冒充"未处理"。若 `thenOptional` 位于流程末尾且节点 Skipped，最终流程结果是 `Accepted(optionalScopeEntry)`。

类型与作用域合同：

- 仅接受同类型 `O -> O` 的四种重载：`Operation<O, O>` 实例、`Class<? extends Operation<O, O>>`、`Class` + qualifier、以及 `Flow<O, O>` 子流程。Skipped 不携带输出，跨类型 `O -> N` 节点无法为后续步骤提供 `N`，调用在 Java 编译期失败。
- 跨类型场景应显式编排同输出类型的兜底，例如 `Flow.firstApplicable(candidate, defaultFlow)`，两个分支均为 `Flow<O, N>`。
- `thenOptional(Flow<O, O>)` 把整个子流程作为 optional scope。若子流程先产生 Accepted 中间值、随后最终 Skipped，identity 使用的是子流程入口值，中间值不会泄漏到外层。
- `Rejected` 与 `Failed` 不匹配 SKIPPED 触发器，仍按普通传播规则短路。

---

# 3. 八节点运行时语义【前四种 L1 · 后四种 L2】

编译后的运行时计划封闭为八种 `NodeDescriptor.Kind`，不提供自定义节点：

## 3.1 INVOKE（调用）【L1】

执行绑定的 `Operation`。`Flow.step`、`then(Operation)` 与 `use` 会创建 INVOKE；`thenOptional(Operation)` 则把 INVOKE 包装在 SKIPPED Fallback + Identity 结构内，而不是新增节点 Kind。要点：

- **实例与 Bean 双绑定**：可直接传入 `Operation` 实例，也可传入 `Class<? extends Operation>` 与可选限定符 `qualifier`，编译期由容器解析为单例 Bean（见 4.5）。
- **use 上下文调用**：`use(operation, project, merge)` 通过 `project` 从当前输出派生入参、`merge` 合并原输出与 Operation 输出，用于"调用外部服务但不丢失主上下文"。
- **异常收敛**：异常被转换为 `OPERATION_EXCEPTION` 稳定 Failed（见 8.1）；Operation 返回 null 同样被严格拒绝。

## 3.2 SEQUENCE（顺序）【L1】

按声明顺序执行子节点，普通 Sequence 仍然仅由 Accepted 推进。相邻匿名 `then` 会被扁平化为同一子节点列表；`Flow.scope(name, body)` 创建具名 Sequence，是 Fallback 恢复边界与超时作用域的载体。

`thenOptional` 不放宽 Sequence reducer：它插入的 SKIPPED Fallback 会在节点 Skipped 时通过 Identity 产出 Accepted(entry)，Sequence 看到的仍然是标准 Accepted。

## 3.3 ROUTE（路由）【L1】

先执行 selector Operation（支持 Lambda、实例或 `Class`/`qualifier` 容器 Bean）得到路由键，再按键选中分支：

- 键匹配为精确 `equals`；重复 case 在构建期以 `DUPLICATE_ROUTE_CASE` 即时抛出 `FlowBuildException`（不走聚合，见 8.2）。
- 声明 `otherwise` 时未匹配走默认分支；声明 `withoutOtherwise` 时未匹配整体 Skipped（`NO_ROUTE`，见 2.3）。
- 路由键对框架是不透明值（opaque）：`caseOf` 接受任意类型键，图渲染等结构面只呈现可稳定序列化的形态（见 [可视化文档](flow-graph.md)）。

## 3.4 FALLBACK（降级/恢复）【L1】

按触发器尝试分支序列，触发器只有 SKIPPED 与 FAILED 两种：

- `Flow.firstApplicable(flowA, flowB, ...)`：Trigger 为 SKIPPED，依次尝试，首个非 Skipped 即结果（见 2.3）。
- `flow.thenOptional(next)`：Trigger 为 SKIPPED；next 最终 Skipped 时执行 Identity 分支，以 optional scope 入口值恢复为 Accepted（见 2.4）。
- `flow.recoverWith(recoveryFlow)`：Trigger 为 FAILED，主分支 Failed 时以 `Recovery<I>`（`input()` 原始 scope 输入 + `failure()` 最终 Failure）作为恢复分支输入。
- 恢复分支自身 Failed 时按外层规则继续传播，不会无限递归。

## 3.5 PARALLEL（并行）【L2】

`Flow.parallel(Branch.of(name, flowA), Branch.of(name2, flowB)).join(strategy)`：

- **wait-all 合同**：等待全部分支业务完成（含 Rejected/Skipped/Failed）后才交给 `JoinStrategy`；分支名构建期校验唯一（`DUPLICATE_BRANCH`）。
- **取消不进 join**：执行被取消时分支与整体直接走 CANCELLED 出口，JoinStrategy 不会被调用（见第 7 节）。
- **分支限制**：分支内不能 `await`（`PARALLEL_AWAIT`）、不能使用 `PersistentPolicy`（`PARALLEL_PERSISTENT_POLICY`），均构建期拒绝。
- Local 执行器分支由 worker 线程池真并发执行；Durable 执行器按声明顺序串行驱动分支（崩溃一致性合同允许的简化，见 [Durable 文档](flow-durable.md)）。
- 内置合并策略（`ParallelResults` 上的方法，配合 `join` 使用）：

| 策略 | 语义 | 失败/弃权码 |
| :--- | :--- | :--- |
| `allAccepted()` | 全 Accepted 才 Accepted（`Values`）；否则短路返回首个非 Accepted | 无（透传首个非 Accepted） |
| `firstAccepted()` | 按声明顺序首个 Accepted；无则 Skipped | `NO_APPLICABLE_BRANCH`（Skipped） |
| `quorum(n)` | 成功分支数 ≥ n 才 Accepted（`Values`）；否则 Failed | `QUORUM_NOT_REACHED`（Failed） |
| `homogeneousCollect()` | 全 Accepted 才按声明顺序收集为列表；否则首个非 Accepted | 无（透传首个非 Accepted） |

```java
Branch<String, Integer> length = Branch.of("length",
        (context, value) -> Outcome.accepted(value.length()));
Branch<String, Integer> upper = Branch.of("upper",
        (context, value) -> Outcome.accepted(value.toUpperCase()));

Flow<String, String> flow = Flow.<String>parallel(length, upper)
        .join(results -> results.homogeneousCollect().map(String::valueOf));
```

## 3.6 AWAIT（挂起）【L2】

`flow.await(ResumePoint.named("approval"))` 在当前点挂起：

- Local：`run` 返回 `FlowResult.Suspended`，携带 `Suspension`（不透明、单次消费、仅可由产生它的 `LocalExecutable` 恢复——跨执行器恢复抛 `IllegalArgumentException`）。
- 恢复：`executable.resume(suspension, point, signal)`，信号类型由 `ResumePoint<R>` 静态约束；恢复后输出 `Resumed<S, R>`（`state()` 挂起前 scope entry + `signal()` 注入信号）。
- `ResumePoint` 的 name 在同一 Flow 内唯一（`DUPLICATE_RESUME_POINT`）；resume 时 name 不匹配抛 `IllegalArgumentException`；Suspension 二次消费抛 `IllegalStateException`；`FlowResult.Suspended.awaiting(point)` 可在恢复前断言挂起点是否匹配。

```java
ResumePoint<String> approval = ResumePoint.named("approval");
Flow<Integer, String> flow = Flow.<Integer>identity()
        .await(approval)
        .then((context, resumed) -> Outcome.accepted(
                resumed.state() + ":" + resumed.signal()));

LocalExecutable<Integer, String> executable = Local.compile(flow);
FlowResult<String> result = executable.run(42);

if (result instanceof FlowResult.Suspended
        && ((FlowResult.Suspended<String>) result).awaiting(approval)) {
    String output = executable
            .resume(((FlowResult.Suspended<String>) result).suspension(),
                    approval, "yes")
            .requireAccepted(); // "42:yes"
}
```

## 3.7 CONTROL（控制）【L2】

包裹当前 Flow 的控制节点，四种 `ControlKind`：`POLICY`、`PERSISTENT_POLICY`、`RETRY`、`TIMEOUT`（见第 5 节）。

## 3.8 COMPLETE（完结）【L1】

静态终点：`Flow.identity()`（透传输入）、`Flow.accepted(v)` / `rejected(r)` / `skipped(r)` / `failed(f)`（固定 Outcome）。

---

# 4. 扩展点合同【Operation 为 L1 · 其余 L2】

## 4.1 Operation

```java
public interface Operation<I, O> {
    Outcome<O> execute(OperationContext context, I input) throws Exception;
}
```

- 同步、可复用、线程安全；实现应避免持有跨调用可变状态。
- `OperationContext` 只暴露 `metadata()`、稳定幂等键 `invocationId()`、`cancellation()` 取消信号，以及把 `CompletionStage` 同步阻塞为值的 `await(stage)`（取消时抛 `CancellationException`）。
- 返回 null 会被严格拒绝；抛出的异常统一转稳定 Failed（第 8 节）。

## 4.2 Policy（无状态网关）

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

## 4.3 PersistentPolicy（持久化控制策略）

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

## 4.4 JoinStrategy

```java
public interface JoinStrategy<O> {
    Outcome<O> join(ParallelResults results);
}
```

接收声明顺序的分支结果集合，返回单个 Outcome。`ParallelResults` 提供按 token 的类型化查找 `outcome(branch)` 与内置合并策略（见 3.5）；`Values` 仅包含 Accepted 分支的输出并支持 `get(branch)` / `contains(branch)`（对未成功分支调用 `get` 抛 `IllegalStateException`）。

## 4.5 组件绑定与解析模型

所有扩展点（`Operation`、`Policy`、`PersistentPolicy`）均支持两种绑定形态：

1. **显式实例绑定**：`Flow.step(opInstance)`，适合纯函数、测试桩与内联函数；
2. **声明式类型绑定**：`Flow.step(OpClass.class)`、`Flow.step(OpClass.class, "beanQualifier")`，适合 Spring 与本地容器环境。

核心语义与保证：**编译期一次性解析**（`Local.compile` / `BeanFlows.compile` / `DurableRuntime.compile` 阶段由 `OperationResolver` 解析并缓存单例引用，运行期零反射开销）；**代理原样保留**（`@Transactional`、AOP 切面及动态代理解析后原样执行）；**结构与执行解耦**（Flow AST 只记录契约类型与限定符，支持跨环境复用与图表渲染）；**严格诊断**（Bean 缺失或类型不匹配在编译阶段抛 `FlowBuildException`，杜绝运行期隐式故障）。

完整用法与 Spring 最佳实践详见 [Bean 容器集成](flow-bean.md)，自定义扩展的端到端示例见 [扩展指南](flow-extension.md)。

---

# 5. 控制机制：retry、timeout 与 policy 挂载【L2】

## 5.1 retry

```java
Flow<String, String> flaky = Flow.step(riskyOperation).retry(Retry.maxAttempts(3)); // 无间隔
Flow<String, String> backed = Flow.step(riskyOperation)
        .retry(new Retry(3, Duration.ofSeconds(2)));  // 固定 2s backoff
```

- `maxAttempts` 含首次执行（`Retry.maxAttempts(n)` 或 `new Retry(n, backoff)`，支持 `withBackoff(d)` 派生）；Failed 时按 backoff 间隔重新执行 body，直到成功或次数耗尽。
- 重试沿用同一 scope entry 且 `invocationId` 保持稳定（`flowId:flowVersion:executionId:path` 不变），外部副作用可据此幂等。
- Local 中 backoff 大于零时执行挂起为等待态（受超时与取消约束）；Durable 中落 ACTIVE+wakeAt 快照，返回 `DurableResult.Active`，由外部调度在 wakeAt 后 `recover` 唤醒（见 [Durable 文档](flow-durable.md)）。

## 5.2 timeout

```java
Flow<String, String> guarded = flow.timeout(Duration.ofSeconds(5));
```

- 为当前作用域设置截止时间；超时产生 `TIMEOUT` 稳定 Failed 并终止最近的作用域（scope）。
- Duration 必须为正，否则构建期抛出 `IllegalArgumentException`。
- 作用于 `Flow.scope(name, body)` 边界或最近的组合作用域。

## 5.3 policy / persistentPolicy / retry / timeout 挂载顺序

`.policy(...)` / `.persistentPolicy(...)` / `.retry(...)` / `.timeout(...)` 均包裹在"当前 Flow"之上，链式调用自外向内生效。例如 `flow.policy(p, k).retry(r)` 表示 policy 先评估、其保护体内才应用 retry；`flow.retry(r).policy(p, k)` 则每次重试都会重新评估 policy。

---

# 6. Local 执行驱动模型与死锁防御【L2】

## 6.1 你何时需要关心

**只有当你的 Flow 用到嵌套 parallel、parallel 分支内 timeout，或传入自定义非 ForkJoinPool 线程池时，才需要读本节的线程模型合同。** 只用 `run(input)` 同步驱动、或始终使用默认 `commonPool()` 的用户可跳过 6.3/6.4——框架的两级静态校验会在危险配置下直接拒绝启动，而非放任死锁。

## 6.2 执行入口全景

`LocalExecutable` 是编译后的本地内存执行句柄，支持同步、异步驱动与线程池治理：

| 方法 | 签名要点 | 语义说明 |
| :--- | :--- | :--- |
| **同步运行** | `run(I input)` / `run(I input, Cancellation cancellation)` | 在当前调用方线程同步驱动 `SerialMachine`，返回 `FlowResult<O>` |
| **异步运行** | `runAsync(I input)`（可叠加 `Cancellation` / `ExecutorService dispatcher` 重载） | 提交到 `dispatcher` 线程池异步执行，返回 `CompletionStage<FlowResult<O>>`；未指定时默认 `ForkJoinPool.commonPool()` |
| **同步恢复** | `resume(Suspension s, ResumePoint point, R signal)`（可叠加 `Cancellation`） | 在当前线程恢复挂起执行，单次消费句柄 |
| **异步恢复** | `resumeAsync(Suspension s, ResumePoint point, R signal, ...)`（含 Cancellation / dispatcher 组合重载） | 在 `dispatcher` 线程池中异步恢复执行，返回 `CompletionStage<FlowResult<O>>` |
| **重配置线程池** | `withExecutor(ExecutorService workerExecutor)` | 派生绑定新 Worker 线程池的 `LocalExecutable` 实例 |

```java
LocalExecutable<OrderReq, Receipt> executable = Local.compile(orderFlow);
FlowResult<Receipt> syncResult = executable.run(request);          // 同步
executable.runAsync(request).thenAccept(result ->                  // 异步
        System.out.println("完成: " + result.requireAccepted()));
```

## 6.3 线程模型：Dispatcher vs Worker

引擎内部将线程职责严格解耦为两类：

- **Dispatcher（调度线程池）**：用于 `runAsync` / `resumeAsync` 的顶层发起调度；
- **Worker Executor（工作线程池）**：用于 `Parallel` 分支并发执行、异步 Stage 转换与超时监控。

## 6.4 线程池饥饿与死锁防御规则（Deadlock Defense）

在基于阻塞等待（如 `Parallel` wait-all、`timeout`）的流水线编排中，错误的线程池复用会导致严重的**嵌套任务饥饿死锁（Thread Starvation Deadlock）**。框架在底层提供两级静态防线，均以 `IllegalArgumentException` 快速失败：

| 防线 | 触发条件（编译期静态分析 Flow 结构） | 校验规则 |
| :--- | :--- | :--- |
| **Dispatcher 与 Worker 隔离校验**（`validateDispatcherNotStarvingWorker`） | Flow 含任意 Parallel 分支或 TIMEOUT 控制时 | 严禁将**同一个非 ForkJoinPool** 的有界/单线程池（如 `newSingleThreadExecutor()`、`FixedThreadPool`）同时作为 `dispatcher` 与 `workerExecutor`，防止顶层任务占满线程池后子任务永远排队 |
| **嵌套并行线程补偿校验**（`validateWorkerExecutor`） | Parallel 分支内部还嵌套 Parallel 或 TIMEOUT 时 | Worker 线程池必须是支持线程协同补偿的 `ForkJoinPool`（或默认 `commonPool()`），确保子任务阻塞等待时自动扩容补偿工作线程 |

---

# 7. 取消合同：true wait-all【L2】

`Cancellation` 是协作式取消令牌：`cancel()` CAS 置位、中断当前绑定的运行线程，并通过父子链接令牌（`Cancellation.linked(parent)`）向子令牌级联传播——Parallel 各分支持父子链接令牌。

**true wait-all 退出保证**：Parallel 汇合采用"真正阻塞等待所有已启动分支的工作线程完全退出、任何情况下不允许虚假返回"的合同：

- 全部分支业务完成后才进入 `JoinStrategy`；取消发生时取消整组分支并等待它们全部退出。
- **忽略中断会延迟返回**：Operation 若捕获 `InterruptedException` 后既不恢复中断标志也不尽快返回，会让退出等待被拖长，但框架仍会等到线程真正退出后才返回，不泄漏分支线程。
- 分支被取消时其结果不会进入 `JoinStrategy`——取消出口绕过 wait-all/join，直接使整体走 CANCELLED 生命周期（图渲染中体现为"CANCELLED 不进 join"，见 [可视化文档](flow-graph.md)）。
- Operation 内部可通过 `context.cancellation().isCancelled()` 主动检查，或用 `context.await(stage)` 让阻塞等待响应取消。

```java
Cancellation cancellation = Cancellation.create();
FlowResult<String> result = Local.compile(flow).run(input, cancellation);
// 其他线程：cancellation.cancel();
// result 为 FlowResult.Cancelled，仅携带 executionId
```

---

# 8. 异常收敛与完整诊断码【L1 + L2】

框架在 `FlowDiagnosticCodes`（`com.team4u.framework.flow.model`）中规范标准诊断码。Operation、selector、JoinStrategy、Policy 回调中抛出的异常不会逃逸为未捕获异常，而是被转换为携带稳定码的 Outcome，随四态规则正常传播。另有 `FATAL_ERROR`：分支线程抛出 `Error` 时不转换为 Outcome，而是取消整组分支并原样重抛。

## 8.1 运行时稳定码（Outcome 携带）

**Failed 失败码**（携带于 `Failure`）：

| 来源分类 | 稳定码 | 触发场景说明 |
| :--- | :--- | :--- |
| 执行异常 | `OPERATION_EXCEPTION` | 操作（`Operation`）执行中抛出未受检异常或业务异常（含返回 null 的拒绝） |
| 线程中断 | `OPERATION_INTERRUPTED` | 操作执行线程被外部物理中断 |
| 协作取消 | `OPERATION_CANCELLED` | 操作在执行中检测到 `Cancellation` 取消信号生效 |
| 超时阻断 | `TIMEOUT` | 作用域执行耗时超过 `flow.timeout(Duration)` 时限 |
| 线程拒绝 | `EXECUTOR_REJECTED` | 并行分支或异步调度被底层线程池拒绝（`RejectedExecutionException`） |
| 策略中断 | `WAIT_INTERRUPTED` | 持久化/控制策略在延时退避等待时被中断 |
| 策略异常 | `POLICY_EXCEPTION` | `Policy.before/after` 或 `PersistentPolicy` 回调执行抛出异常 |
| 汇聚异常 | `JOIN_EXCEPTION` | 并行汇聚策略（`JoinStrategy`）执行合并逻辑时抛出异常 |
| 并行分支异常 | `PARALLEL_EXCEPTION` | Parallel 分支线程以异常结束时，该分支结果转此失败码 |
| 并行等待中断 | `PARALLEL_INTERRUPTED` | Parallel wait-all 等待线程被外部中断（非取消） |
| 分支取消 | `PARALLEL_BRANCH_CANCELLED` | 单个 Parallel 分支被取消但整体未走 CANCELLED 出口时 |
| 法定未达 | `QUORUM_NOT_REACHED` | `quorum(n)` 汇聚时成功分支数不足 |

**Skipped 弃权码**（携带于 `Reason`）：

| 稳定码 | 触发场景说明 |
| :--- | :--- |
| `NO_ROUTE` | 路由未命中任何 `caseOf` 且无 `otherwise`（整体 Skipped，见 2.3） |
| `NO_APPLICABLE_BRANCH` | `firstAccepted()` 汇聚时无任何分支 Accepted |

## 8.2 编译期静态校验码（FlowBuildException）

在 `Local.compile` / `BeanFlows.compile` / `DurableRuntime.compile` 阶段，`Compiler` 静态校验拓扑与依赖，发现结构缺陷时聚合所有违规项，封装为 `FlowBuildException` 一次性抛出：

| 诊断码 | 校验类型 | 违规原因 |
| :--- | :--- | :--- |
| `DUPLICATE_LABEL` | 节点标识 | 同一节点叠加多个 `named` 标签（一个节点只允许一个） |
| `DUPLICATE_SCOPE` | 作用域 | 同名具名作用域（`scope`） |
| `DUPLICATE_BRANCH` | 并行分支 | `parallel` 内重复分支名（`Branch.name`） |
| `DUPLICATE_RESUME_POINT` | 挂起点 | 同名挂起点（`ResumePoint.named`） |
| `DUPLICATE_PATH` | 拓扑路径 | 编译器生成的 path 冲突（path 如 `$/1/selector` 不承诺跨版本稳定，不可持久化） |
| `PARALLEL_AWAIT` | 非法挂起 | `parallel` 分支内使用 `await` |
| `PARALLEL_PERSISTENT_POLICY` | 非法策略 | `parallel` 分支内使用 `persistentPolicy` |
| `INVALID_BINDING` | 契约违规 | 绑定的类未实现预期扩展点接口（如 Operation/Policy） |
| `MISSING_BINDING` | 依赖缺失 | 容器中未找到声明的 Bean（Class 或 Qualifier 不匹配） |
| `BINDING_TYPE` | 类型不匹配 | 解析出的 Bean 实例类型与声明契约不一致 |
| `IMPLEMENTATION_CLASS` | 反射诊断 | 无法从代理对象提取真实实现类信息 |

`DUPLICATE_ROUTE_CASE`（重复路由键）在 `caseOf` 调用时即时抛出 `FlowBuildException`，不进入上述聚合。

```java
try {
    Local.compile(invalidFlow);
} catch (FlowBuildException e) {
    for (FlowBuildException.Problem problem : e.problems()) {
        System.err.printf("错误码: %s, 路径: %s, 详情: %s%n",
                problem.code(), problem.path(), problem.message());
    }
}
```

---

# 9. 观测【全层】

`FlowObserver` 在 Local 与 Durable 执行中通用，事件类型为闭集枚举：

| 分类 | 事件 |
| :--- | :--- |
| 流程生命周期 | `FLOW_STARTED` / `FLOW_COMPLETED` / `FLOW_SUSPENDED` / `FLOW_CANCELLED` |
| 节点生命周期 | `NODE_STARTED` / `NODE_COMPLETED` |
| 路由与降级 | `ROUTE_SELECTED` / `FALLBACK_SELECTED` |
| 策略评估 | `POLICY_BEFORE` / `POLICY_AFTER` / `POLICY_WAITING` |
| 并行汇合 | `PARALLEL_STARTED` / `PARALLEL_BRANCH_COMPLETED` / `PARALLEL_JOINED` |

回调抛出的运行时异常被框架隔离，不影响执行。`FlowObserver.composite(...)` 顺序广播，单观察者异常被吞掉。test 模块的 `TraceCollector` 是线程安全的开箱收集器（见 [测试文档](flow-test.md)）。
