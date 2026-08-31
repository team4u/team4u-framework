# 扩展机制与 SPI 开发指南

`team4u-flow` 遵循“**扩展点开放、运行时节点封闭**”的架构设计原则：

- **运行时节点封闭（Closed PlanNode Set）**：编译后的执行计划仅由八种确定节点（`INVOKE`, `SEQUENCE`, `ROUTE`, `FALLBACK`, `PARALLEL`, `AWAIT`, `CONTROL`, `COMPLETE`）组成，保证内核语义的一致性与稳定性；
- **扩展点开放（Open SPIs）**：业务逻辑收敛于 `Operation`，治理控制收敛于 `Policy` / `PersistentPolicy`，并行合并收敛于 `JoinStrategy`，容器依赖解析收敛于 `OperationResolver`，状态持久化收敛于 `StateMapper` 与 `DurableStore`，全链路监控收敛于 `FlowObserver` / `DurableObserver`，自定义执行引擎收敛于 `ExecutableFlowVisitor`。

---

## 扩展点总览与选型矩阵

| 扩展接口 | 核心方法签名 | 适用场景 |
| :--- | :--- | :--- |
| **`Operation<I, O>`** | `Outcome<O> execute(OperationContext ctx, I input)` | 业务转换、RPC 调用、数据库操作与外部副作用 |
| **`Policy<K>`** | `Gate before(PolicyContext, K)` + `after(...)` | 无状态准入、租户限流、动态风控与权限鉴权 |
| **`PersistentPolicy<K, S>`** | `initialState` + `before` + `after` | 有状态且需跨重启持久化的治理策略（如延时重试、配额窗口） |
| **`JoinStrategy<O>`** | `Outcome<O> join(ParallelResults results)` | 并行分支执行结果的自定义合并、加权与仲裁 |
| **`OperationResolver`** | `Object resolve(Class<?> contract, String qualifier)` | 容器依赖解析（Spring / Guice 或自定义 IoC 容器集成） |
| **`StateMapper`** | `StoredValue encode(Object)` / `decode(StoredValue)` | Durable 持久化应用状态的确定性序列化与反序列化 |
| **`DurableStore`** | `load(id)` + `compareAndSet(id, revision, snapshot)` | 快照存储适配（如 Redis、MySQL、PostgreSQL 等外部存储） |
| **`FlowObserver` / `DurableObserver`** | `void onEvent(Event)` | 全链路执行追踪、监控指标收集与审计日志 |
| **`ExecutableFlowVisitor<R>`** | `visitInvoke` / `visitSequence` / ... | 自建自定义执行器、静态分析工具或安全审计引擎 |

---

## 业务操作扩展 (`Operation`)

`Operation` 是承载业务逻辑的核心扩展点：

```java
public interface Operation<I, O> {
    Outcome<O> execute(OperationContext context, I input) throws Exception;
}
```

### 开发规范与最佳实践
- **线程安全与无状态**：实现类通常为单例，应保持无状态，避免在实例中维护跨请求的可变字段；
- **上下文辅助**：`OperationContext` 提供稳定幂等键 `invocationId()`（`flowId:flowVersion:executionId:path`），可直接作为分布式防重 Token；提供 `cancellation()` 用于协作取消检测；
- **异常收敛**：未捕获异常统一由框架收敛为 `OPERATION_EXCEPTION` 诊断码的 `Failed`；
- **可选步骤弃权**：若当前数据不适用但不应阻断流程，返回 `Outcome.skipped(reason)` 并通过 `thenOptional` 编排。

---

## 治理控制扩展 (`Policy` 与 `PersistentPolicy`)

### 无状态策略：`Policy<K>`

用于在流程执行前后进行拦截裁决：

```java
public interface Policy<K> {
    Gate before(PolicyContext context, K key);
    default void after(PolicyContext context, K key, Completion completion) { }
}
```

- `Gate` 决策闭集：`Gate.proceed()`（放行）、`Gate.reject(Reason)`（业务拒绝）、`Gate.fail(Failure)`（技术故障）；
- `after` 回调接收四态完成摘要 `Completion`（包含四态 `kind` 及对应的 `Reason` / `Failure`），用于监控指标统计或资源清理；
- 通过 `flow.policy(policy, keyFunction)` 将输入对象映射为策略键 $K$。

### 有状态持久化策略：`PersistentPolicy<K, S>`

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

## 并行汇聚扩展 (`JoinStrategy`)

```java
@FunctionalInterface
public interface JoinStrategy<O> {
    Outcome<O> join(ParallelResults results) throws Exception;
}
```

接收全部并行分支的执行结果并合并为单个 `Outcome`。`ParallelResults` 提供按令牌检索的 `results.outcome(branch)` 与内置策略（`allAccepted`、`firstAccepted`、`quorum` 等）：

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

## 容器解析扩展 (`OperationResolver`)

```java
public interface OperationResolver {
    Object resolve(Class<?> contract, String qualifier);
    default Class<?> implementationClass(Object resolved) { return resolved.getClass(); }
}
```

- 在编译阶段按 Class 和限定符解析对应的单例 Bean；
- 默认 `OperationResolver.rejecting()` 在遇到类绑定时抛出 `IllegalStateException`；
- `team4u-flow-bean` 模块内置的 `BeanOperationResolver` 通过 `BeanManager` 门面统一桥接 Spring 容器与本地容器。

---

## 状态编解码扩展 (`StateMapper`)

在 Durable 持久化模式下，`StateMapper` 负责业务对象与字节载荷之间的确定性编解码：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.team4u.framework.flow.durable.snapshot.StateMapper;
import com.team4u.framework.flow.durable.snapshot.StoredValue;

public class JacksonStateMapper implements StateMapper {

    private static final String CODEC = "json:jackson";
    private static final int VERSION = 1;

    private final ObjectMapper objectMapper;

    public JacksonStateMapper(ObjectMapper objectMapper) {
        // 必须开启 Map Key 排序，确保序列化字节逐字节确定性！
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

> [!NOTE]
> `StoredValue` 由三个字段构成：`codecId`（编码器标识，如 `"json:jackson"`）、
> `codecVersion`（编码器版本号，正整数）与 `payload`（业务载荷字节数组）。
> 解码方通常依据 `codecId` / `codecVersion` 自行路由反序列化逻辑，因此业务类型信息
> 需编码进 payload（如 JSON 自描述）或由固定的槽位类型约定承载。

---

## 快照存储扩展 (`DurableStore`)

若不使用 `team4u-flow-durable-kv`，可直接实现 `DurableStore` 适配原生数据库：

```java
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.store.DurableStore;
import java.util.Optional;

public class JdbcDurableStore implements DurableStore {

    @Override
    public Optional<DurableSnapshot> load(String executionId) {
        // 执行 SQL: SELECT snapshot_bytes FROM flow_snapshot WHERE execution_id = ?
        // 反序列化为 DurableSnapshot 并返回
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

## 全链路观察者扩展 (`FlowObserver` 与 `DurableObserver`)

```java
import com.team4u.framework.flow.api.FlowObserver;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingFlowObserver implements FlowObserver {

    @Override
    public void onEvent(Event event) {
        log.info("[FLOW-EVENT] type={}, executionId={}, path={}, attrs={}",
                event.type(), event.metadata().executionId(),
                event.descriptor().path(), event.attributes());
    }
}
```

### 观察者契约要点

- **异常隔离**：观察者回调中抛出的任何运行时异常都会被框架捕获并记录日志（首次 warn、后续按实例限流 debug），绝不影响主流程执行结果；`Error` 不被拦截，原样传播；
- **无操作短路（`isNoop()`）**：`FlowObserver` 提供 `default boolean isNoop()`（默认 `false`），
  引擎在热路径上据此短路事件对象与属性字典的构造分配；`FlowObserver.noop()` 返回的实例
  覆写返回 `true`，自定义空观察者若可安全跳过全部事件，建议覆写本方法返回 `true` 以获得更佳性能；
- **组合广播**：`FlowObserver.composite(observers...)` 可将多个观察者按顺序广播（单个观察者异常不会中断其余观察者；仅当全部成员均为 noop 时复合观察者才报告 noop）；
- **线程模型契约**：实现必须线程安全；在包含并行分支的流程中，`PARALLEL_STARTED` /
  `PARALLEL_BRANCH_COMPLETED` 等并行分支事件可能从多个工作线程**并发到达**，
  仅保证单分支内事件有序，跨分支之间无全序保证；实现内部若维护可变状态（计数器、缓冲队列等）
  必须使用并发安全容器；
- **事件配对性**：`NODE_STARTED` / `NODE_COMPLETED` 与 `POLICY_BEFORE` / `POLICY_AFTER` 事件
  在非取消、非超时、非重试轮次的正常路径上成对出现；当执行因取消、超时或
  `PersistentPolicy` 声明重试轮次（RetryAt）而中断或循环时，事件可能不成对。

---

## 双投影 SPI：可执行合同与结构描述

`Flow<I, O>` 对外提供两条职责严格隔离的投影通道：

```text
Flow<I, O>
  ├── describe(flowId) -> FlowDescription          结构描述通道（纯静态模型）
  └── project(resolver, visitor) -> R              可执行计划通道（强类型合同）
```

### ExecutableFlowVisitor：可执行计划编译器

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
- `Local.compile` 与 `DurableRuntime.compile` 内部均通过实现 `ExecutableFlowVisitor` 构建各自的执行计划；
- 开发者可基于该 SPI 自定义执行内核或进行代码静态安全审计。

---

## 关联章节与进一步阅读

- 掌握综合业务场景实战：[实战案例库与生产模式](flow-sample.md)
- 了解全链路诊断码体系：[诊断码体系与故障排查手册](flow-diagnostics.md)
- 查阅单元测试与断言工具：[测试支持与测试套件](flow-test.md)
