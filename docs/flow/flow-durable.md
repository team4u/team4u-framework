# Durable 持久化执行

> 层级：L3 引擎层 · 前置：flow-semantics · 模块：team4u-flow-durable

team4u-flow-durable 是独立于核心的持久化执行器组件——同一份 Flow 定义，换一个执行器，获得崩溃恢复能力。

它与 core 的关系，如同 kv 之于 kv-store-jdbc：按需引入独立模块，core 不做任何改动。你在 `Local` 上验证通过的 `Flow<I, O>`，原样交给 `DurableRuntime.compile`，即可获得节点级检查点与 at-least-once 恢复能力——不另造 DSL，不改一行流程定义。

**读者画像**：本篇假设你已理解 L1 四态模型（Outcome，见 [flow-semantics](flow-semantics.md)）与 L2 执行模型（parallel / await / PersistentPolicy / FlowResult 三态）。在此之上，本篇一次性列清新增的五项心智负担：

| 新增心智负担 | 一句话合同 |
| :--- | :--- |
| invocationId 幂等 | 外部副作用必须以 `invocationId` 作为幂等键去重 |
| at-least-once | 崩溃恢复可能重复驱动未提交检查点的节点，框架不承诺 exactly-once |
| revision CAS | 多实例并发操作同一执行时，冲突方以 `REVISION_CONFLICT` 失败 |
| StateMapper 确定性 | 同一状态值多次编码必须产生 `equals` 相等的 `StoredValue` |
| wakeAt 外部调度 | 框架不自带定时器，到点唤醒（`recover`）是调用方的责任 |

---

# 1. 架构

```mermaid
graph TD
    F["Flow&lt;I, O&gt;<br/>不可变逻辑定义"] --> RT["DurableRuntime<br/>builder(store)<br/>.stateMapper/.operationResolver/.observer/.durableObserver/.executor"]
    RT --> EX["DurableExecutable&lt;I, O&gt;<br/>绑定 (flowId, flowVersion)"]
    EX --> CMD["start / resume / recover / cancel / snapshot<br/>startAsync / resumeAsync"]
    CMD --> M["DurableMachine<br/>单命令驱动至下一稳定边界"]
    M <--> CK["Checkpoints<br/>节点边界 CAS 提交"]
    CK <--> ST[("DurableStore<br/>load + compareAndSet(expectedRevision)")]
    CK <--> SM["StateMapper<br/>确定性 encode/decode StoredValue"]
    M --> RES["DurableResult<br/>Completed / Suspended / Active / Cancelled"]
```

核心理念：

1. **完全复用同一份 Flow 定义**：不另造 DSL，本地纯内存验证通过的流程直接交给 `DurableRuntime.compile`。
2. **零 Lambda 序列化**：快照仅含框架元数据与编码后的 `StoredValue` 槽位，绝不序列化 Java 代码、Operation 实例或 Lambda。
3. **revision CAS 检查点**：每次状态推进以乐观锁提交，多实例并发操作同一执行时冲突方失败。
4. **版本强隔离**：以 `flowId + flowVersion` 显式标识；快照与可执行不匹配时直接拒绝，不做结构猜测与自动迁移。

`thenOptional` 在核心 DSL 中已脱糖为 `FALLBACK(trigger=SKIPPED)` + `COMPLETE(identity)`，Durable 不新增控制种类或快照字段。因此 Local 与 Durable 共享完全相同的 Skipped 原值透传、Rejected/Failed 短路及 optional scope 入口值语义。

命令统一遵循模式：**load（无副作用）→ 校验生命周期 → 状态变更 → CAS 提交 → 驱动机器**。若进程在 CAS 提交后、驱动完成前崩溃，命令向调用方重抛原异常；由于快照已落库，重新 compile 后 `recover` 即可从最后提交的检查点继续。

---

# 2. 快速上手

```java
DurableStore store = new InMemoryDurableStore();          // 生产自建 JDBC 等实现
DurableRuntime runtime = DurableRuntime.builder(store)
        .stateMapper(mapper)                // 可选，默认 DefaultStateMapper.INSTANCE
        .operationResolver(resolver)        // 可选，默认 rejecting
        .observer(flowObserver)             // 可选，FlowObserver
        .durableObserver(durableObserver)   // 可选，检查点事件
        .executor(executorService)          // 可选，仅借用不关闭
        .build();

DurableExecutable<OrderRequest, Receipt> executable =
        runtime.compile(flow, "order-fulfill", 3);   // flowId + flowVersion（必须 >= 1）

DurableResult<Receipt> result = executable.start("order-20240101-0001", request);
```

命令集：

| 命令 | 语义 |
| :--- | :--- |
| `start(executionId, input)` | 开启新执行：先 CAS 创建 ACTIVE 初始快照（revision=1，create-if-absent），再驱动首段；重复 start 以 `EXECUTION_EXISTS` 拒绝 |
| `resume(executionId, pointName, signal)` | 向 SUSPENDED 执行注入信号并驱动续接（两段 CAS，见第 5 节） |
| `recover(executionId)` | 恢复 ACTIVE 执行：从最后提交的快照解码并继续驱动；非 ACTIVE 以 `LIFECYCLE_MISMATCH` 拒绝 |
| `cancel(executionId)` | 取消 ACTIVE/SUSPENDED 执行：CAS 落 CANCELLED 终态 |
| `snapshot(executionId)` | 读取快照（无副作用） |
| `startAsync` / `resumeAsync` | 借用调用方 executor 的异步版本，返回 `CompletionStage`；未配置 executor 抛 `ASYNC_EXECUTOR_MISSING` |

生命周期与结果四态：

- 生命周期：`ACTIVE -> COMPLETED | SUSPENDED | CANCELLED`；`SUSPENDED --resume--> ACTIVE -> ...`。
- 退避等待（Retry backoff / PersistentPolicy WaitUntil/RetryAt）落 ACTIVE+wakeAt 快照并退出线程（Parked），命令返回 `DurableResult.Active(wakeAt)`，由外部调度在 wakeAt 后调用 `recover` 唤醒。

| `DurableResult<O>` | 携带物 | 语义 |
| :--- | :--- | :--- |
| `Completed` | `Outcome<O>` + snapshot | 终态，携带业务四态结果 |
| `Suspended` | `resumePoint` + snapshot | 等待外部恢复信号注入 |
| `Active` | `wakeAt`（`Optional<Instant>`）+ snapshot | 退避等待中，到点由外部 `recover` 唤醒 |
| `Cancelled` | snapshot | 终态 |

便捷取值：`result.requireAccepted()` 仅在 `Completed` 且结果为 `Outcome.Accepted` 时返回业务载荷，否则抛 `IllegalStateException`。

---

# 3. 快照内容边界与 StateMapper

## 3.1 快照只含元数据与编码槽

`DurableSnapshot` 信封字段：

- 标识：`executionId`、`flowId`、`flowVersion`、`formatId/formatVersion`（快照格式标识与版本，当前 `team4u-typed-flow-durable:1`）；
- 推进：`revision`（乐观锁版本，单调递增）、`lifecycle`；
- 恢复协调：`awaitingPoint`（等待的挂起点）、`pendingResume`（是否含待消费信号）；
- 框架元数据：`frameMetadata`（帧栈结构骨架，框架内部二进制编码）；
- 业务槽：`slots: Map<String, StoredValue>`——按角色名存放的编码值：初始输入（`input`）、帧栈携带的业务值（`node:<path>`）、PersistentPolicy 的键与状态（`key:<path>` / `policy:<path>`）、恢复信号（`resume:<name>`）。

**绝不**包含：Operation/Policy 实例、Lambda、Class 引用或任何可执行回调。`StoredValue` 对运行时是不透明数据（`codecId` + `codecVersion` + `payload` 字节）。

信封自带生命周期不变式校验：SUSPENDED 必须有 `awaitingPoint` 且无待消费信号；ACTIVE 的 `pendingResume` 与 `awaitingPoint` 必须同时存在或同时缺席；终态不得携带任何恢复状态。

## 3.2 StateMapper 确定性契约

```java
public interface StateMapper {
    StoredValue encode(Object value) throws Exception;
    Object decode(StoredValue storedValue) throws Exception;
}
```

**确定性契约**：同一状态值多次 `encode` 必须产生 `equals` 相等的 `StoredValue`（相同 codec 标识与字节序列）。切勿在载荷中混入随机盐、当前时间戳或未排序的 Map 键值对——resume 信号的幂等比较（第 5 节）完全依赖该保证。

- 默认实现 `DefaultStateMapper.INSTANCE` 支持基础标量（String/Integer/Long/Boolean/Double/Float/Short/Byte/Character）、`byte[]` 与 `Instant`。
- **复杂领域对象与 DTO 序列化**：
  - `SerializerStateMapper`：基于函数式接口桥接外部序列化器（如 Jackson、Fastjson、Protobuf 等），保持核心引擎零多余依赖的边界；
  - `CompositeStateMapper`：组合模式链式映射器，原生标量优先走 `DefaultStateMapper`，复杂实体自动回退到后序映射器。

```java
// 结合 Jackson 构造复合状态映射器（优先标量，复杂实体走 Jackson）
ObjectMapper objectMapper = new ObjectMapper();
SerializerStateMapper jacksonMapper = new SerializerStateMapper(
        "json:jackson", 1,
        obj -> objectMapper.writeValueAsBytes(obj),
        bytes -> objectMapper.readValue(bytes, Object.class)
);

// 复合映射器：标量走 DefaultStateMapper，DTO 走 Jackson
StateMapper compositeMapper = CompositeStateMapper.withDefault(jacksonMapper);

DurableRuntime runtime = DurableRuntime.builder(store)
        .stateMapper(compositeMapper)
        .build();
```

---

# 4. invocationId 与 at-least-once

每个节点执行注入稳定幂等键：

```text
invocationId = flowId:flowVersion:executionId:path
```

- 同一执行内重试（Retry/PersistentPolicy）与崩溃恢复重放时，`invocationId` 保持绝对稳定。
- 外部副作用（支付扣款、库存预占）应以 `invocationId` 作为幂等键，把"框架可能重复驱动同一节点"转化为"外部系统按 key 去重"，实现 **at-least-once 外部写 + 幂等去重 = 有效 exactly-once 业务效果**。
- 恢复重放时节点是否真正重执行取决于快照推进边界：已提交检查点的节点不会重复执行，未提交的会从检查点重放——两种情况下幂等键不变。

---

# 5. resume 两段 CAS：信号先落库

`resume(executionId, pointName, signal)` 分两步独立提交：

```text
第一步（信号落库）：
  load 快照 -> 校验 lifecycle 与 awaitingPoint -> 信号 encode 进 resume:<name> 槽
  -> CAS(revision, ACTIVE + pendingResume=true) 提交

第二步（续接驱动）：
  重新 load -> 解码重建 pendingSignal -> 驱动机器消费信号并推进
```

崩溃语义：

- 崩溃在第一步之前：执行仍为 SUSPENDED，可重新 resume（同值或异值信号均可）。
- 崩溃在第一步之后、第二步之前：快照已 ACTIVE+pendingResume；再次 resume 同值信号幂等重驱动（编码确定性契约在此发挥作用），异值信号以 `RESUME_SIGNAL_CONFLICT` 拒绝；也可直接 `recover` 从快照续跑。
- `RESUME_POINT_MISMATCH`：挂起点 name 与快照 `awaitingPoint` 不一致时拒绝；对 COMPLETED/CANCELLED 终态或无待消费信号的 ACTIVE 执行 resume，以 `LIFECYCLE_MISMATCH` 拒绝。

---

# 6. PersistentPolicy 状态持久化

`PersistentPolicy<K, S>` 的不可变状态 `S` 由框架编码进快照 `policy:<path>` 槽位：

- `initialState(key)` 首次评估时构造初始状态；
- before 的 `WaitUntil(instant, state)` 落 ACTIVE+wakeAt 快照，命令返回 `DurableResult.Active(wakeAt)`；到点后 `recover` 重新评估；
- after 的 `RetryAt(instant, state)` 同样以 ACTIVE+wakeAt 挂起；`Return(state)` 落终态；
- 每次状态变更随检查点 CAS 提交，崩溃后从快照恢复最后一次已提交的状态——策略状态与执行位置在同一快照内原子推进。

PersistentPolicy 不能用于 Parallel 分支：编译期以 `PARALLEL_PERSISTENT_POLICY` 静态拒绝（与 Local 共用同一编译校验）。

---

# 7. 并行串行驱动声明

**Durable 的 Parallel 分支按声明顺序串行驱动**：前序分支完成后才开始后序，不做并发执行。

- 这是崩溃一致性合同允许的简化：串行驱动下每个分支边界都是清晰的检查点边界，无需处理多分支并发写的 revision 风暴。
- wait-all 语义不变：全部分支完成后仍交给同一 `JoinStrategy` 合并，`invocationId` 规则不变。
- 需要真实并发执行时使用 Core 的 Local 执行器（其分支由 worker 线程池并发驱动，配合 `team4u-flow-test` 的 `ParallelBarrier` 可验证真并发）。

---

# 8. (flowId, flowVersion) 兼容边界

- Durable 身份是 `(flowId, flowVersion)` 二元组；快照在 load 时校验归属，不匹配以 `FLOW_MISMATCH` 拒绝。
- **快照格式不做跨版本兼容**：`FORMAT_MISMATCH` 直接拒绝旧/新格式快照，框架不提供迁移工具。
- 流程结构变更（增删节点、改变分支）必须递增 `flowVersion` 并以新版本 compile；旧版本执行只能由同版本可执行恢复。
- 结构投影（`flow.describe`）的节点 path 用于观测定位，**不承诺跨版本稳定**，不要把 path 持久化到业务库做跨版本比对。
- `executionId` 全局唯一由调用方保证；`start` 前先检查 `store.load(executionId)` 或依赖 `EXECUTION_EXISTS` 拒绝。

---

# 9. Durable 观测与事件体系（DurableObserver）

除了标准的 `FlowObserver` 事件外，持久化执行器还提供专用 `DurableObserver` 接口，监听快照存储与状态机恢复全流程（观察者异常被吞掉，绝不影响执行）：

| 事件类型（`DurableObserver.Type`） | 触发时机 | 关键扩展属性（`attributes`） |
| :--- | :--- | :--- |
| `CHECKPOINT_COMMITTED` | 节点边界快照成功通过 CAS 提交入库 | `kind`（检查点类别）、`path`（节点路径） |
| `CHECKPOINT_RESTORED` | 调用 `recover` 从底层存储成功恢复快照并重建内存状态机 | `revision`（恢复时快照版本号） |
| `RESUME_SIGNAL_PERSISTED` | 调用 `resume` 第一步完成，恢复信号成功落库为 `resume:<name>` 槽 | `resumePoint`（目标挂起点名称） |

`DurableObserver.Event` 对象包含：`type`（事件类型）、`at`（时间戳）、`metadata`（flowId/version/executionId/nodePath）、`revision`（快照乐观锁版本）、`lifecycle`（当前生命周期状态）与 `attributes`（键值扩展属性）。

---

# 10. DurableStore SPI 与异常体系

```java
public interface DurableStore {
    Optional<DurableSnapshot> load(String executionId);
    boolean compareAndSet(String executionId, long expectedRevision, DurableSnapshot update);
}
```

- `expectedRevision = -1` 表示 create-if-absent（start 命令用）。
- `compareAndSet` 返回 false 表示 revision 冲突（并发写）；实现抛出的底层存储异常由框架统一包装为 `STORE_FAILURE`。
- 实现须无副作用 load（仅读取）。内置 `InMemoryDurableStore` 用于测试与演示；生产环境自建 JDBC/Redis 等实现（一张表、一列字节即可承载快照信封）。

## 10.1 DurableException 错误码闭集与处理指引

当持久化操作发生冲突、版本不匹配或存储故障时，框架统一抛出 `DurableException`，其内部包含强类型错误枚举 `DurableException.Error`（闭集，共 14 个）：

| 错误码（`DurableException.Error`） | 触发原因 | 推荐处理策略 |
| :--- | :--- | :--- |
| `INVALID_DEFINITION` | 编译期或驱动期发现非法流程定义（如 Parallel 分支以非 COMPLETED 结束） | 修正 Flow 定义后重新 compile |
| `INVALID_CONFIGURATION` | 非法运行时配置 | 核对 `DurableRuntime.builder` 各项配置 |
| `EXECUTION_EXISTS` | `start` 时指定的 `executionId` 在存储中已存在 | 检查业务幂等流水号生成逻辑，避免重复启动同名执行 |
| `EXECUTION_NOT_FOUND` | `resume`/`recover`/`cancel` 时指定的 `executionId` 不存在 | 确认执行 ID 是否正确，或检查底层存储是否有丢失/过期清理 |
| `FLOW_MISMATCH` | 快照所属的 `flowId` 或 `flowVersion` 与当前可执行不一致 | 确保使用与快照完全一致的 Flow 版本进行编译与恢复，不可跨版本混用 |
| `FORMAT_MISMATCH` | 快照的 `formatId` 或 `formatVersion` 与当前运行时格式不兼容 | 检查框架版本兼容性，旧版本快照需使用对应版本运行时恢复 |
| `FRAME_MISMATCH` | 快照帧栈元数据损坏，或恢复时帧栈拓扑与当前代码不匹配 | 数据完整性校验失败，需排查底层存储是否损坏或代码结构是否变更 |
| `CODEC_FAILURE` | 使用 `StateMapper` 编码或解码状态插槽（`StoredValue`）失败，或快照槽位与帧栈引用不一致 | 检查业务 DTO 是否变更导致无法反序列化，或补充注册缺失的类型编解码器 |
| `STORE_FAILURE` | 底层 `DurableStore` 在执行 `load` 或 `compareAndSet` 时抛出数据库/IO 异常 | 检查数据库连通性、网络抖动或事务超时，由调用方做重试拦截 |
| `REVISION_CONFLICT` | 并发操作同一 `executionId` 时 CAS 乐观锁版本冲突 | 多实例并发写竞争，调用方可稍后重试读取最新快照 |
| `LIFECYCLE_MISMATCH` | 在非法的生命周期状态下调用命令（如 resume 已完成的执行，或 recover 非 ACTIVE 执行） | 检查流程调用时序，避免对终态（COMPLETED/CANCELLED）执行重复发起驱动 |
| `RESUME_POINT_MISMATCH` | `resume` 时传入的挂起点名称与快照中实际等待的 `awaitingPoint` 不一致 | 核对外部回调系统通知的挂起点标识是否准确 |
| `RESUME_SIGNAL_CONFLICT` | 恢复信号落库后、消费前发生崩溃，再次 resume 时传入了不同内容的信号 | 确保同一挂起点在重放时注入相同信号内容（基于 StateMapper 确定性编码） |
| `ASYNC_EXECUTOR_MISSING` | 调用 `startAsync` / `resumeAsync` 但在构建 `DurableRuntime` 时未配置 `executor` | 在 `DurableRuntime.builder(store).executor(...)` 中显式配置线程池 |

深入实战（自定义 StateMapper / DurableStore / PersistentPolicy）见 [flow-extension](flow-extension.md)；完整下单示例见 [flow-sample](flow-sample.md)。
