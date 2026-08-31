# 扩展机制与 SPI

`team4u-flow` 遵循“**扩展点开放、运行时节点封闭**”的架构设计原则：

- **运行时节点封闭**：编译后的执行计划仅由八种确定节点（INVOKE / SEQUENCE / ROUTE / FALLBACK / PARALLEL / AWAIT / CONTROL / COMPLETE）组成，保证内核语义的一致性与稳定性；
- **扩展点开放**：业务逻辑收敛于 `Operation`，治理控制收敛于 `Policy` / `PersistentPolicy`，并行合并收敛于 `JoinStrategy`，容器依赖解析收敛于 `OperationResolver`，自定义执行器与结构接入收敛于双投影 SPI。

---

## 扩展点总览与选型

| 扩展接口 | 核心方法签名 | 适用场景 |
| :--- | :--- | :--- |
| `Operation<I, O>` | `Outcome<O> execute(OperationContext ctx, I input)` | 业务转换、RPC 调用、外部副作用与数据处理 |
| `Policy<K>` | `Gate before(PolicyContext, K)` + `after(...)` | 无状态准入、租户限流、熔断与鉴权 |
| `PersistentPolicy<K, S>` | `initialState` + `before` + `after` | 有状态且需跨重启持久化的治理策略（如延时重试、滑动窗口） |
| `JoinStrategy<O>` | `Outcome<O> join(ParallelResults results)` | 并行分支执行结果的自定义合并与裁决 |
| `OperationResolver` | `Object resolve(Class<?> contract, String qualifier)` | 容器依赖解析（Spring 或自定义 IoC 容器集成） |
| `StateMapper` | `StoredValue encode(Object)` / `decode(StoredValue)` | Durable 持久化应用状态的自定义序列化与反序列化 |
| `DurableStore` | `load(id)` + `compareAndSet(id, revision, snapshot)` | 快照存储适配（如 JDBC、Redis 等外部存储） |
| `FlowObserver` / `DurableObserver` | `void onEvent(Event)` | 全链路执行追踪、监控指标收集与审计日志 |
| `ExecutableFlowVisitor<R>` | `visitInvoke` / `visitSequence` / ... | 自建执行器、静态分析工具或安全审计工具 |

---

## 业务操作扩展 (Operation)

`Operation` 是承载业务逻辑的核心扩展点：

```java
public interface Operation<I, O> {
    Outcome<O> execute(OperationContext context, I input) throws Exception;
}
```

实现规范与最佳实践：
- **线程安全与无状态**：实现应保持无状态，避免在实例中维护跨请求的可变字段；
- **上下文辅助**：`OperationContext` 提供稳定幂等键 `invocationId()`（`flowId:flowVersion:executionId:path`），可直接作为分布式防重 Token；提供 `await(CompletionStage)` 用于在响应取消的同时安全完成异步等待；
- **异常收敛**：未捕获异常统一由框架收敛为 `OPERATION_EXCEPTION` 诊断码的 `Failed`；
- **可选步骤弃权**：若当前数据不适用但不应阻断流程，返回 `Outcome.skipped(reason)` 并通过 `thenOptional` 编排。

---

## 治理控制扩展 (Policy 与 PersistentPolicy)

### Policy 无状态网关

用于在流程执行前后进行拦截裁决：

```java
public interface Policy<K> {
    Gate before(PolicyContext context, K key);
    default void after(PolicyContext context, K key, Completion completion) { }
}
```

- `Gate` 决策闭集：`Gate.proceed()`（放行）、`Gate.reject(Reason)`（业务拒绝）、`Gate.fail(Failure)`（技术故障）；
- `after` 回调接收四态完成摘要 `Completion`（不含业务数据载荷），用于监控指标统计或资源清理；
- 通过 `flow.policy(policy, keyFunction)` 将输入对象映射为策略键 $K$。

### PersistentPolicy 有状态策略

用于状态需由框架自动持久化并在崩溃后原位恢复的场景：

```java
public interface PersistentPolicy<K, S> {
    S initialState(K key);
    Before<S> before(PolicyContext context, K key, S state);
    After<S> after(PolicyContext context, K key, S state, Completion completion);
}
```

- `before` 返回 `Proceed`、`WaitUntil`、`Reject`、`Fail`；
- `after` 返回 `Return`、`RetryAt`；
- 状态对象 $S$ 必须不可变且满足 `StateMapper` 确定性编解码契约。

---

## 并行汇聚扩展 (JoinStrategy)

```java
public interface JoinStrategy<O> {
    Outcome<O> join(ParallelResults results);
}
```

接收全部并行分支的执行结果并合并为单个 `Outcome`。`ParallelResults` 提供按 Token 检索的 `results.outcome(branch)` 与内置策略（`allAccepted`、`firstAccepted`、`quorum` 等）：

```java
JoinStrategy<String> customStrategy = results -> {
    Outcome<RiskReport> risk = results.outcome(riskBranch);
    if (!(risk instanceof Outcome.Accepted)) {
        return Outcome.skipped(Reason.of("RISK_UNAVAILABLE", "风控结果不可用"));
    }
    return results.outcome(stockBranch).map(stock ->
            ((Outcome.Accepted<RiskReport>) risk).value().summary() + "/" + stock.summary());
};
```

---

## 容器解析扩展 (OperationResolver)

### OperationResolver 接口契约

```java
public interface OperationResolver {
    Object resolve(Class<?> contract, String qualifier);
    default Class<?> implementationClass(Object resolved) { ... }
}
```

- 在编译阶段按 Class 和限定符解析对应的单例 Bean；
- 默认 `OperationResolver.rejecting()` 在遇到类绑定时抛出 `IllegalStateException`。

### Bean 容器集成 (team4u-flow-bean)

框架内置的 `BeanOperationResolver` 通过 `BeanManager` 统一桥接 Spring 容器与本地容器，透明保留 `@Transactional`、AOP 切面与动态代理：

```java
import com.team4u.framework.flow.bean.BeanFlows;

// 1. 本地同步编译
LocalExecutable<OrderRequest, Receipt> local = BeanFlows.compile(flow);

// 2. Durable 持久化运行时挂载
DurableRuntime runtime = DurableRuntime.builder(store)
        .operationResolver(BeanFlows.resolver())
        .build();
```

---

## 自定义状态映射器 (StateMapper)

在 Durable 持久化模式下，`StateMapper` 负责业务对象与字节载荷之间的编解码：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.team4u.framework.flow.durable.snapshot.StateMapper;
import com.team4u.framework.flow.durable.snapshot.StoredValue;

public class JacksonStateMapper implements StateMapper {

    private static final String CODEC = "json-jackson";
    private static final int VERSION = 1;

    private final ObjectMapper objectMapper;

    public JacksonStateMapper(ObjectMapper objectMapper) {
        // 开启 Map Key 排序，确保序列化字节确定性
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

> [!IMPORTANT]
> **确定性契约**：同一对象多次调用 `encode` 必须生成逐字节相同的载荷。避免在载荷中包含随机 ID、时间戳或未排序的 Map 键值对，以确保 resume 恢复信号幂等比对的准确性。

---

## 自定义持久化存储与策略实战

### PersistentPolicy 实战：滑动限流窗口

```java
public class RateWindowPolicy implements PersistentPolicy<String, long[]> {

    @Override
    public long[] initialState(String key) {
        return new long[]{0L, 0L}; // [windowStartMillis, count]
    }

    @Override
    public Before<long[]> before(PolicyContext context, String key, long[] state) {
        long now = System.currentTimeMillis();
        long[] nextState = new long[]{state[0], state[1]};
        if (now - nextState[0] >= 60_000L) {
            nextState[0] = now;
            nextState[1] = 0;
        }
        if (nextState[1] >= 100) {
            return PersistentPolicy.reject(
                    Reason.of("RATE_LIMITED", "窗口内请求超限"), nextState);
        }
        nextState[1] = nextState[1] + 1;
        return PersistentPolicy.proceed(nextState);
    }

    @Override
    public After<long[]> after(PolicyContext context, String key,
                               long[] state, Completion completion) {
        return PersistentPolicy.returning(state);
    }
}
```

### DurableStore 实战：JDBC 存储适配

```java
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.store.DurableStore;
import java.util.Optional;

public class JdbcDurableStore implements DurableStore {

    @Override
    public Optional<DurableSnapshot> load(String executionId) {
        // 执行 SQL: SELECT snapshot_bytes FROM flow_snapshot WHERE execution_id = ?
        // 反序列化为 DurableSnapshot
        return Optional.empty();
    }

    @Override
    public boolean compareAndSet(String executionId, long expectedRevision,
                                 DurableSnapshot update) {
        // 执行 SQL: UPDATE flow_snapshot SET revision = ?, snapshot_bytes = ?
        // WHERE execution_id = ? AND (revision = ? OR (? = -1 AND NOT EXISTS ...))
        // 受影响行数为 1 时返回 true，为 0 时返回 false（乐观锁并发冲突）
        return true;
    }
}
```

---

## 双投影 SPI：可执行合同与结构描述

`Flow<I, O>` 对外提供两条职责严格隔离的投影通道：

```text
Flow<I, O>
  ├── describe(flowId) -> FlowDescription          结构描述通道（纯静态模型）
  └── project(resolver, visitor) -> R              可执行计划通道（强类型合同）
```

### ExecutableFlowVisitor：可执行合同

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

- 输入为已完成静态拓扑校验与 Bean 绑定的运行时计划；
- `DurableRuntime.compile` 内部通过实现 `ExecutableFlowVisitor` 构建持久化执行计划；
- 开发者可基于该 SPI 自定义执行内核或进行代码静态分析。

### FlowDescription：只读结构描述

- `flow.describe(flowId)` 导出冻结的只读数据模型 `FlowDescription`；
- 仅包含拓扑路径、节点类型、标签与绑定描述，**不包含任何回调实例或业务值**；
- `team4u-flow-graph` 模块完全基于该模型生成 Mermaid 图与文本树。

### 双投影选择指引

| 业务需求 | 推荐方式 |
| :--- | :--- |
| 图表渲染 / 文档生成 / 拓扑比对 | 使用 `describe(flowId)` 导出 `FlowDescription` |
| 自定义执行器 / 依赖分析 / 静态校验 | 使用 `project(resolver, visitor)` 实现 `ExecutableFlowVisitor` |
| 标准同步或持久化流程执行 | 直接使用 `Local.compile` 或 `DurableRuntime.compile` |

---

## 下一步

- 掌握综合业务场景实战：[实战案例](flow-sample.md)
