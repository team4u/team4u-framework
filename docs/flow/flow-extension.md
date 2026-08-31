# 扩展机制与 SPI

> 层级：L1 → L3 逐级 · 前置：quick-start · 模块：team4u-flow

`team4u-flow` 的扩展哲学是**"扩展点开放、运行时节点封闭"**：

- **运行时封闭**：编译后的运行时计划只有八种节点（INVOKE / SEQUENCE / ROUTE / FALLBACK / PARALLEL / AWAIT / CONTROL / COMPLETE），闭集不可增删——语义演进靠官方引擎统一保证，不能塞进"第九种节点"。
- **扩展点开放**：所有可变性收敛到 `api` 与 `spi` 包显式声明的接口上——业务行为进 `Operation`，治理裁决进 `Policy` / `PersistentPolicy`，并行合并进 `JoinStrategy`，容器解析进 `OperationResolver`，结构接入进双投影 SPI。每个扩展点按复杂度分层归位：多数人到 L1 为止。

---

# 1. 何时需要自定义扩展点

先看这张决策表，再决定往哪层走。**多数场景只需要实现 Operation**——它是唯一的业务扩展点，其余扩展点都有明确的触发条件：

| 触发条件 | 需要实现 | 层级 |
| :--- | :--- | :--- |
| 新增一个业务转换、外部调用或副作用步骤 | `Operation` | L1 |
| "这步对当前输入不适用，但流程继续" | `Operation` 返回 `Skipped`，配 `thenOptional` 挂载 | L1 |
| 并行分支结果要按业务规则合并，内置四种策略不够用 | `JoinStrategy` | L2 |
| 步骤前要限流/熔断/鉴权，且无需跨重启记忆状态 | `Policy` | L2 |
| 控制状态要在崩溃重启后存活（退避窗口、审批等待） | `PersistentPolicy` | L2 挂载，L3 才有状态持久化 |
| 想用 `Class` 声明步骤并从自己的容器解析实例 | `OperationResolver`（Spring 用户直接用 team4u-flow-bean） | L2/L3 |
| 要对执行做链路追踪、指标、审计 | `FlowObserver`（Durable 侧另有 `DurableObserver`） | L2/L3 |
| Durable 的应用状态要换成自定义编码格式 | `StateMapper` | L3 |
| 快照要落到自建存储（JDBC/Redis） | `DurableStore` | L3 |
| 想接入自己的 DI 容器或造一个新执行器 | 双投影 SPI（见第 9 章压轴） | L2/L3 |

以上都不需要？直接用 `Flow.step(...)` + 内置 DSL 即可，本文可以不看。

# 2. 扩展点分层总览

扩展点按层归位（运行时节点不可扩展）：

| 扩展点 | 签名要点 | 归属层 | 受众 |
| :--- | :--- | :--- | :--- |
| `Operation<I, O>` | `Outcome<O> execute(OperationContext ctx, I input) throws Exception` | L1 | 业务开发者 |
| `JoinStrategy<O>` | `Outcome<O> join(ParallelResults results)` | L2 | 业务开发者 |
| `Policy<K>` | `Gate before(PolicyContext, K)` + `default void after(...)` | L2 | 业务开发者 |
| `PersistentPolicy<K, S>` | `S initialState(K)` + `Before<S> before(...)` + `After<S> after(...)` | L2 挂载 / L3 状态持久化 | 业务开发者 |
| `FlowObserver` | `void onEvent(Event)` | L2（Local/Durable 通用） | 业务/运维 |
| `OperationResolver` | `Object resolve(Class<?> contract, String qualifier)` | L2/L3 | 框架集成者 |
| `ExecutableFlowVisitor<R>` / `FlowDescription` | 双投影 SPI（第 9 章） | L2/L3 | 框架集成者 |
| `StateMapper` | `StoredValue encode(Object)` / `Object decode(StoredValue)` | L3 | 平台开发者 |
| `DurableStore` | `Optional<DurableSnapshot> load(String)` / `boolean compareAndSet(String, long, DurableSnapshot)` | L3 | 平台开发者 |
| `DurableObserver` | `void onEvent(Event)` | L3 | 运维/平台 |

挂载方式速查：`Operation` 经 `Flow.step/then/thenOptional/use`、`Branch.of`；`Policy` / `PersistentPolicy` 经 `flow.policy(...)` / `flow.persistentPolicy(...)`；`JoinStrategy` 经 `Flow.parallel(...).join(strategy)`；`OperationResolver` / `FlowObserver` 经 `Local.compile(flow, resolver[, observer, executor])`；L3 组件经 `DurableRuntime.builder(store)` 对应方法。

# 3. Operation：唯一的业务扩展点（L1）

```java
public interface Operation<I, O> {
    Outcome<O> execute(OperationContext context, I input) throws Exception;
}
```

- 同步、可复用、线程安全；实现应避免持有跨调用可变状态。
- `OperationContext` 提供 `metadata()`（flowId/version、executionId、nodePath、label）、稳定幂等键 `invocationId()`（`flowId:flowVersion:executionId:path`，重试与恢复重放中恒定，可直接作分布式防重 token）、`cancellation()` 协作式取消信号，以及 `await(CompletionStage)` 辅助（阻塞前后检查取消信号、响应中断、剥离 `ExecutionException` 重抛底层异常）。
- 返回 null 被严格拒绝；抛异常统一转 `OPERATION_EXCEPTION` 稳定 Failed；作用域超时转 `TIMEOUT`。
- 对于"不适用但不应阻断后续"的同类型 `Operation<T, T>`，返回 `Outcome.skipped(reason)` 并通过 `thenOptional` 挂载；不要用 `Accepted(input)` 隐藏未处理状态。

# 4. Policy 与 PersistentPolicy：治理裁决（L2）

## 4.1 Policy：无状态网关

```java
public interface Policy<K> {
    Gate before(PolicyContext context, K key);
    default void after(PolicyContext context, K key, Completion completion) { }
}
```

- `Gate` 闭集：`Gate.proceed()` / `Gate.reject(Reason)`（产生 Rejected，可触发降级/短路）/ `Gate.fail(Failure)`（产生 Failed，可触发重试/恢复）。
- `PolicyContext` 暴露 `metadata()`、当前重试 `attempt()`（从 1 递增）与取消信号。
- `Completion` 是不含载荷的四态完成摘要（Kind + 关联 Reason/Failure），供 `after` 做统计或释放资源。

## 4.2 PersistentPolicy：有状态、可跨重启

```java
public interface PersistentPolicy<K, S> {
    S initialState(K key);
    Before<S> before(PolicyContext context, K key, S state);
    After<S> after(PolicyContext context, K key, S state, Completion completion);
}
```

- Before 闭集：`Proceed(state)` / `WaitUntil(instant, state)`（推迟到绝对时刻再评估，Local 侧阻塞等待，Durable 侧落 `wakeAt` 由外部调度唤醒）/ `Reject(reason, state)` / `Fail(failure, state)`；After 闭集：`Return(state)` / `RetryAt(instant, state)`（指定时刻唤醒重试）。
- 状态 `S` 必须不可变且可被 StateMapper 确定性编码；Durable 引擎在每个检查点中持久化它，崩溃后状态机原位恢复（见第 8.1 节实战）。
- 静态工厂：`PersistentPolicy.proceed/waitUntil/reject/fail`、`PersistentPolicy.returning/retryAt`。
- 不能用于 Parallel 分支（构建期拒绝）。

# 5. JoinStrategy：并行汇聚（L2）

```java
public interface JoinStrategy<O> {
    Outcome<O> join(ParallelResults results);
}
```

接收声明顺序的分支结果。`ParallelResults` 提供 `outcome(branch)` 类型化查找、`branches()`、内置策略 `allAccepted()` / `firstAccepted()`（无 Accepted 时返回 `NO_APPLICABLE_BRANCH` 的 Skipped）/ `quorum(n)`（未达阈值返回 `QUORUM_NOT_REACHED` 的 Failed）/ `homogeneousCollect()`，以及 `Values.get(branch)/contains(branch)`。自定义示例：

```java
JoinStrategy<String> riskAware = results -> {
    Outcome<RiskReport> risk = results.outcome(riskBranch);
    if (!(risk instanceof Outcome.Accepted)) {
        return Outcome.skipped(Reason.of("RISK_UNAVAILABLE", "风控结果不可用"));
    }
    return results.outcome(stockBranch).map(stock ->
            ((Outcome.Accepted<RiskReport>) risk).value().summary() + "/" + stock.summary());
};
```

# 6. OperationResolver：容器解析（L2/L3，框架集成者）

```java
public interface OperationResolver {
    Object resolve(Class<?> contract, String qualifier);
    default Class<?> implementationClass(Object resolved) { ... }
}
```

- 编译期一次性解析 `Flow.step(Class)` / `step(Class, qualifier)` 这类按类型声明的绑定；返回实例被原样持有（Spring 代理不拆包）。
- 默认 `rejecting()` 实现在命中时抛 `IllegalStateException`，适合全部用实例绑定的场景。
- `implementationClass` 默认在 JDK 代理场景回退到首个非扩展点接口（排除 Operation/Policy/PersistentPolicy），保证 NodeDescriptor 呈现稳定实现类。

## 6.1 Bean 容器集成（team4u-flow-bean）

流程 DSL 在构建期无需持有具体对象实例，可直接通过 `Class<? extends Operation>`、`Class<? extends Policy>` 与可选限定符（Bean 名称）进行声明式编排。`BeanOperationResolver` 通过 `BeanManager` 门面从 Spring 容器或本地容器中解析绑定，解析结果原样绑定至执行计划（AOP 拦截器与 `@Transactional` 代理完整保留）：

```java
import com.team4u.framework.flow.bean.BeanFlows;

// 1. 使用全局 BeanManager (配合 Spring: @Import(Team4uBeanConfiguration.class))
LocalExecutable<OrderRequest, Receipt> executable = BeanFlows.compile(flow);

// 2. 显式指定 BeanManager
LocalExecutable<OrderRequest, Receipt> explicit =
        BeanFlows.compile(flow, beanManager);

// 3. 构造 resolver 配合 Local.compile
LocalExecutable<OrderRequest, Receipt> manual =
        Local.compile(flow, BeanFlows.resolver());

// 4. 构造 resolver 配合 DurableRuntime (持久化长流程)
DurableRuntime runtime = DurableRuntime.builder(store)
        .operationResolver(BeanFlows.resolver())
        .build();
```

- **解析规则**：`qualifier == null` 时调用 `beanManager.getRequiredBean(contract)`；非 null 时调用 `beanManager.getBean(qualifier)`（不存在抛 `NoSuchBeanDefinitionException`）并严格校验 `contract.isInstance(bean)`。
- **性能优势**：`compile` 期完成一次性解析并缓存单例引用，运行期为直接方法调用，零反射损耗。
- **深入指南**：完整 Spring 配置、动态代理拦截机制与常见排错详见 [Bean 容器集成](flow-bean.md)。

# 7. 实战：自定义 StateMapper（L3）

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.flow.durable.snapshot.StateMapper;
import com.team4u.framework.flow.durable.snapshot.StoredValue;

public class JacksonStateMapper implements StateMapper {

    private static final String CODEC = "json-jackson";
    private static final int VERSION = 1;

    private final ObjectMapper objectMapper;

    public JacksonStateMapper(ObjectMapper objectMapper) {
        // 确定性要求：排序 key、禁用随机性注入
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Override
    public StoredValue encode(Object value) throws Exception {
        byte[] payload = objectMapper.writeValueAsBytes(value);
        return new StoredValue(CODEC, VERSION, payload);
    }

    @Override
    public Object decode(StoredValue storedValue) throws Exception {
        return objectMapper.readValue(storedValue.payload(), Object.class);
    }
}
```

**确定性契约**：同一值多次 `encode` 必须产生 `equals` 相等的 `StoredValue`（相同 codecId/codecVersion 与逐字节相同的载荷）。JSON 场景务必：固定字段序（排序 map key）、不携带时间戳/随机 ID、多态类型用稳定 `@JsonTypeInfo` 而非类名字节泄漏。违反契约会破坏 resume 信号幂等比较（同值被误判为 `RESUME_SIGNAL_CONFLICT`）。

注册：

```java
DurableRuntime runtime = DurableRuntime.builder(store)
        .stateMapper(new JacksonStateMapper(objectMapper))
        .build();
```

# 8. 实战：自定义 DurableStore 与 PersistentPolicy（L3）

## 8.1 PersistentPolicy：限流窗口

退避放行策略（窗口内首次调用放行，超出窗口拒绝）：

```java
public class RateWindowPolicy implements PersistentPolicy<String, long[]> {

    @Override
    public long[] initialState(String key) {
        return new long[]{0L, 0L}; // [windowStartMillis, count]
    }

    @Override
    public Before<long[]> before(PolicyContext context, String key, long[] state) {
        long now = System.currentTimeMillis();
        long[] copy = new long[]{state[0], state[1]};
        if (now - copy[0] >= 60_000L) {
            copy[0] = now;
            copy[1] = 0;
        }
        if (copy[1] >= 100) {
            return PersistentPolicy.reject(
                    Reason.of("RATE_LIMITED", "窗口内调用超限"), copy);
        }
        copy[1] = copy[1] + 1;
        return PersistentPolicy.proceed(copy);
    }

    @Override
    public After<long[]> after(PolicyContext context, String key,
                               long[] state, Completion completion) {
        return PersistentPolicy.returning(state); // 计数已在 before 提交
    }
}
```

状态数组每次返回防御性拷贝、从不原地修改——PersistentPolicy 状态必须按值语义演进，框架才能在检查点中安全持久化并跨重启恢复（窗口计数在崩溃后继续累计，不重置）。

## 8.2 DurableStore：快照存储（示意）

存储只需支撑"按 executionId 读取 + 乐观锁替换"，一张表即可承载快照信封：

```java
public class JdbcDurableStore implements DurableStore {

    @Override
    public Optional<DurableSnapshot> load(String executionId) {
        // SELECT snapshot_bytes FROM flow_snapshot WHERE execution_id = ?
        // 反序列化为 DurableSnapshot（信封为可序列化元数据 + StoredValue 槽）
    }

    @Override
    public boolean compareAndSet(String executionId, long expectedRevision,
                                 DurableSnapshot update) {
        // UPDATE flow_snapshot SET revision = ?, snapshot_bytes = ?
        // WHERE execution_id = ? AND (revision = ? OR (? = -1 AND NOT EXISTS ...))
        // 受影响行数为 0 时返回 false（并发冲突）
    }
}
```

实现要点：load 必须无副作用；`expectedRevision == -1` 表示 create-if-absent（保证 start 命令唯一性）；CAS 失败返回 false 而非抛异常（框架会转成 `REVISION_CONFLICT` 边界）；快照字节由调用方序列化（`DurableSnapshot` 为不可变信封，字段可逐一重建）。

---

# 9. 压轴：双投影 SPI——可执行合同 vs 纯描述

> **受众提示**：本章面向**框架集成者**——想接自己的 DI 容器、造新执行器、做静态分析或审计工具的人。普通业务开发者可跳过，`Local.compile` / `DurableRuntime.compile` 已覆盖执行需求。

`Flow<I, O>` 对外开放两条互不重叠的投影通道：

```text
Flow<I, O>
  ├── describe(flowId) -> FlowDescription            结构投影（纯描述）
  └── project(resolver, visitor) -> R                可执行投影（执行合同）
```

## 9.1 ExecutableFlowVisitor：可执行合同

```java
public interface ExecutableFlowVisitor<R> {
    R visitInvoke(NodeDescriptor descriptor, ExecutableBinding binding,
                  Function<Object, Object> project,
                  BiFunction<Object, Object, Object> merge);
    R visitSequence(NodeDescriptor descriptor, List<R> children,
                    Optional<String> scopeName);
    R visitRoute(NodeDescriptor descriptor, ExecutableBinding selectorBinding,
                 List<ExecutableRouteCase<R>> cases, Optional<R> otherwise);
    R visitFallback(NodeDescriptor descriptor, FallbackTrigger trigger,
                    List<R> branches);
    R visitParallel(NodeDescriptor descriptor,
                    List<ExecutableParallelBranch<R>> branches, JoinStrategy<?> join);
    R visitAwait(NodeDescriptor descriptor, ResumePoint<?> resumePoint);
    R visitControl(NodeDescriptor descriptor, ControlKind kind, R body,
                   Optional<ExecutableBinding> binding,
                   Function<Object, Object> keyProjection, Object configuration);
    R visitComplete(NodeDescriptor descriptor, Outcome<?> outcome, boolean identity);
}
```

- 输入是**已由 `Compiler` 校验结构、解析完 class+qualifier 绑定**的运行时计划：`ExecutableBinding` 携带真实实例（`instance()`）与契约信息，`visitInvoke` 暴露擦除后的 `project/merge` 函数，`visitControl` 暴露 `ControlKind`（POLICY / PERSISTENT_POLICY / RETRY / TIMEOUT）、可选绑定、keyProjection 与配置对象（`Retry`/`Duration`）。
- 各 visit 必须返回非 null 投影产物。
- `flow.project(resolver, visitor)` 是公开入口（单参 `project(visitor)` 默认 `rejecting()` 解析器）。**Durable 官方执行器正是经此通道构建**：`DurablePlanCompiler implements ExecutableFlowVisitor<DurablePlanNode>`，`DurableRuntime.compile` 内部即调用 `flow.project(resolver, compiler)`。`Local.compile` 则直接解释 `Compiler` 产出的 `PlanNode` 树——该树正是 visitor 投影的输入，两条路径共享同一套结构校验与绑定解析。
- 自建执行器、审计器、副作用分析器应实现该 SPI，从而获得与官方执行器一致的结构校验与绑定解析。
- **可执行合同**意味着拿到的是能真正驱动的闭包与实例——绝不应当把其中任何回调写入持久化数据（Durable 快照只保存框架元数据与 `StoredValue` 槽位，零 Lambda 序列化）。

## 9.2 FlowDescription：纯描述

- `flow.describe(flowId)` 导出冻结的只读结构模型：`FlowDescription(flowId, root: NodeDescription)`，节点只含结构信息——`path`、`label`、`Kind`、`BindingDescriptor`（契约类/实现类/qualifier），及各节点结构属性（scopeName、trigger、routeCases、parallelBranches、resumePoint、controlKind、configuration、COMPLETE 的 outcome/identity），**不含任何回调实例、业务值或执行状态**。
- 用途：图渲染（graph 模块唯一依赖的结构面）、文档生成、结构比对。
- 不可稳定呈现的值（如 opaque 路由键）在 **graph 渲染层**被统一替换为 `<opaque>` 等占位形态（`FlowGraphFormatters.stableConstant`），描述面本身保留原始对象。
- 节点 `path` 仅保证单次产物内唯一，不承诺跨版本稳定，不可持久化。

## 9.3 选择指引

| 需求 | 选择 |
| :--- | :--- |
| 画图 / 文档 / 结构 diff | `describe` + `FlowDescription` |
| 自建执行器 / 依赖分析 / 静态校验绑定 | `project` + `ExecutableFlowVisitor` |
| 只想跑流程 | `Local.compile` / `DurableRuntime.compile`（Durable 内部已用投影通道） |
