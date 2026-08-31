# 扩展机制与 SPI

`team4u-flow` 的核心封闭为八节点运行时计划，扩展只开放在明确声明的扩展点上；结构分析与执行接入则通过**双投影 SPI** 解耦。

---

# 1. 扩展点清单

可实现的扩展点（运行时节点不可扩展）：

| 扩展点 | 签名要点 | 适用场景 | 挂载方式 |
| :--- | :--- | :--- | :--- |
| `Operation<I, O>` | `Outcome<O> execute(OperationContext ctx, I input) throws Exception` | 业务转换、外部调用、副作用步骤 | `Flow.step/then/use`、`Branch.of` |
| `Policy<K>` | `Gate before(PolicyContext, K)` + `default void after(...)` | 无状态可重放网关：限流、熔断、准入 | `flow.policy(policy, keyFn)` |
| `PersistentPolicy<K, S>` | `S initialState(K)` + `Before<S> before(...)` + `After<S> after(...)` | 状态需跨重启持久化的控制：退避、窗口、审批等待 | `flow.persistentPolicy(policy, keyFn)` |
| `JoinStrategy<O>` | `Outcome<O> join(ParallelResults results)` | Parallel wait-all 后的显式合并 | `Flow.parallel(...).join(strategy)` |
| `OperationResolver` | `Object resolve(Class<?> contract, String qualifier)` | 按契约+限定符解析绑定（容器集成） | `Local.compile(flow, resolver)`、`DurableRuntime.builder(...).operationResolver(...)` |
| `FlowObserver` | `void onEvent(Event)` | 同步执行事件观测（Local/Durable 通用） | `Local.compile(flow, resolver, observer)` 等 |
| `DurableObserver` | `void onEvent(Event)` | 检查点提交/恢复/信号落库事件 | `DurableRuntime.builder(...).durableObserver(...)` |
| `StateMapper` | `StoredValue encode(Object)` / `Object decode(StoredValue)` | 应用状态编解码（`DefaultStateMapper` / `SerializerStateMapper` / `CompositeStateMapper`） | `DurableRuntime.builder(...).stateMapper(...)` |
| `DurableStore` | `Optional<DurableSnapshot> load(String)` / `boolean compareAndSet(String, long, DurableSnapshot)` | 快照存储（JDBC/Redis/内存） | `DurableRuntime.builder(store)` |

## 1.1 Operation

```java
public interface Operation<I, O> {
    Outcome<O> execute(OperationContext context, I input) throws Exception;
}
```

- 同步、可复用、线程安全；实现应避免持有跨调用可变状态。
- `OperationContext` 提供 `metadata()`（flowId/version、executionId、nodePath、label）、稳定幂等键 `invocationId()`（`flowId:flowVersion:executionId:path`）、`cancellation()` 取消信号与 `await(CompletionStage)` 辅助。
- 返回 null 被严格拒绝；抛异常统一转 `OPERATION_EXCEPTION` 稳定 Failed；超时转 `TIMEOUT`。

## 1.2 Policy 与 PersistentPolicy

```java
public interface Policy<K> {
    Gate before(PolicyContext context, K key);
    default void after(PolicyContext context, K key, Completion completion) { }
}
```

- `Gate` 闭集：`Gate.proceed()` / `Gate.reject(Reason)` / `Gate.fail(Failure)`。
- `PolicyContext` 暴露 `metadata()`、当前重试 `attempt()` 与取消信号。
- `Completion` 是无输出值的四态摘要，供 `after` 评估。

```java
public interface PersistentPolicy<K, S> {
    S initialState(K key);
    Before<S> before(PolicyContext context, K key, S state);
    After<S> after(PolicyContext context, K key, S state, Completion completion);
}
```

- Before 闭集：`Proceed(state)` / `WaitUntil(instant, state)` / `Reject(reason, state)` / `Fail(failure, state)`；After 闭集：`Return(state)` / `RetryAt(instant, state)`。
- 状态 `S` 必须不可变且可被 StateMapper 确定性编码；框架在检查点中持久化它。
- 静态工厂（包内可见风格的习惯用法）：`PersistentPolicy.proceed/waitUntil/reject/fail`、`PersistentPolicy.returning/retryAt`。
- 不能用于 Parallel 分支（构建期拒绝）。

## 1.3 JoinStrategy

```java
public interface JoinStrategy<O> {
    Outcome<O> join(ParallelResults results);
}
```

接收声明顺序的分支结果。`ParallelResults` 提供 `outcome(branch)` 类型化查找、`branches()`、内置策略 `allAccepted()/firstAccepted()/quorum(n)/homogeneousCollect()`，以及 `Values.get(branch)/contains(branch)`。自定义示例：

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

## 1.4 OperationResolver

```java
public interface OperationResolver {
    Object resolve(Class<?> contract, String qualifier);
    default Class<?> implementationClass(Object resolved) { ... }
}
```

- 编译期一次性解析 `Flow.step(Class)` / `step(Class, qualifier)` 这类按类型声明的绑定；返回实例被原样持有（Spring 代理不拆包）。
- 默认 `rejecting()` 实现在命中时抛 `IllegalStateException`，适合全部用实例绑定的场景。
- `implementationClass` 默认在 JDK 代理场景回退到首个非扩展点接口，保证 NodeDescriptor 呈现稳定实现类。

---

# 2. 双投影 SPI：可执行合同 vs 纯描述

`Flow<I, O>` 对外开放两条互不重叠的投影通道：

```text
Flow<I, O>
  ├── describe(flowId) -> FlowDescription            结构投影（纯描述）
  └── project(resolver, visitor) -> R                可执行投影（执行合同）
```

## 2.1 ExecutableFlowVisitor：可执行合同

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

- 输入是**已由 `Compiler` 校验结构、解析完 class+qualifier 绑定**的运行时计划：`ExecutableBinding` 携带真实实例（`instance()`）与契约信息，`visitInvoke` 暴露擦除后的 `project/merge` 函数，`visitControl` 暴露 `ControlKind`、可选绑定、keyProjection 与配置对象（`Retry`/`Duration`）。
- 各 visit 必须返回非 null 投影产物。
- 这是 Local 与 Durable 共用的编译通道：`flow.project(resolver, visitor)` 是公开入口，`Local.compile` 与 `DurableRuntime.compile` 内部即走此路径。自建执行器、审计器、副作用分析器应实现该 SPI，从而获得与官方执行器一致的结构校验与绑定解析。
- **可执行合同**意味着拿到的是能真正驱动的闭包与实例——绝不应当把其中任何回调写入持久化数据。

## 2.2 FlowDescription：纯描述

- `flow.describe(flowId)` 导出冻结的只读结构模型：`FlowDescription(flowId, root: NodeDescription)`，树中节点只含 `path`、`label`、`Kind` 与 `BindingDescriptor`（契约类、实现类、qualifier 的字符串面），**不含任何回调实例、业务值或执行状态**。
- 用途：图渲染（graph 模块唯一依赖的结构面）、文档生成、结构比对。
- 不可稳定呈现的值（opaque 路由键、COMPLETE 输出值）在描述面即被替换为占位形态，下游无需再做脱敏。
- 节点 `path` 仅保证单次产物内唯一，不承诺跨版本稳定。

## 2.3 选择指引

| 需求 | 选择 |
| :--- | :--- |
| 画图 / 文档 / 结构 diff | `describe` + `FlowDescription` |
| 自建执行器 / 依赖分析 / 静态校验绑定 | `project` + `ExecutableFlowVisitor` |
| 只想跑流程 | `Local.compile` / `DurableRuntime.compile`（内部已用上述通道） |

---

# 3. 实战：自定义 StateMapper（Jackson JSON）

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.flow.durable.StateMapper;
import com.team4u.framework.flow.durable.StoredValue;

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

**确定性契约**：同一值多次 `encode` 必须产生 `equals` 相等的 `StoredValue`。JSON 场景务必：固定字段序（排序 map key）、不携带时间戳/随机 ID、多态类型用稳定 `@JsonTypeInfo` 而非类名字节泄漏。违反契约会破坏 resume 信号幂等比较（同值被误判为 `RESUME_SIGNAL_CONFLICT`）。

注册：

```java
DurableRuntime runtime = DurableRuntime.builder(store)
        .stateMapper(new JacksonStateMapper(objectMapper))
        .build();
```

---

# 4. 实战：自定义 DurableStore（示意）

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

实现要点：load 必须无副作用；`expectedRevision == -1` 表示 create-if-absent；CAS 失败返回 false 而非抛异常（框架会转成 `REVISION_CONFLICT` 边界）；快照字节由调用方序列化（`DurableSnapshot` 为不可变信封，字段可逐一重建）。

---

# 5. 实战：自定义 JoinStrategy 与 PersistentPolicy

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

---

# 6. Bean 容器集成（team4u-flow-bean）

`BeanOperationResolver` 从 `BeanManager` 解析 class+qualifier 绑定，解析结果原样使用（不替换、不解包代理）：

```java
import com.team4u.framework.flow.bean.BeanFlows;
import com.team4u.framework.flow.bean.BeanOperationResolver;

// 使用全局 BeanManager
LocalExecutable<OrderRequest, Receipt> executable = BeanFlows.compile(flow);

// 显式指定 BeanManager
LocalExecutable<OrderRequest, Receipt> explicit =
        BeanFlows.compile(flow, beanManager);

// 或仅构造 resolver，配合 Local.compile / DurableRuntime
LocalExecutable<OrderRequest, Receipt> manual =
        Local.compile(flow, new BeanOperationResolver(beanManager));
```

绑定声明侧用 class 形式：`Flow.step(RiskScan.class)`、`Flow.step(RiskScan.class, "strict")`；qualifier 为 null 时走 `getRequiredBean(contract)`，非 null 时按 bean 名称查找并校验契约实现。
