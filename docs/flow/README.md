# 轻量化类型化流程组件 (team4u-flow)

# 背景

订单履约、支付结算、逆向退款、复杂数据清洗这类业务，天然由一系列线性转换、条件分支、外部交互与补偿清理逻辑组成。传统实现方式通常面临以下困境：

- **大单体 Service / 脚本方法**：业务逻辑、RPC 调用、异常捕获与状态回滚深陷在一个庞大方法中；单个步骤难以独立测试与 Mock，局部修改极易产生意外副作用。
- **重型工作流引擎（BPMN / 反射式 DSL）**：引入庞大依赖、表结构与运行时容器；强类型退化为 `Object` 与字符串反射，丢失编译期类型安全；启动慢、调用栈深，内存级同步编排"杀鸡用牛刀"。
- **单机同步与持久化恢复割裂**：本地执行与跨进程崩溃恢复通常是不兼容的两套 API；单机验证后要加检查点，往往需要推翻重写流程逻辑。
- **布尔与异常承载分支语义过载**："成功产出 / 业务拒绝 / 无适用分支 / 技术失败"被压缩成 `boolean`、`null` 或 `RuntimeException`，无法在类型层面区分"正常拒绝"与"需要告警的故障"。
- **容器与依赖注入割裂**：纯 Lambda 编排难以注入 Spring 托管的 DAO、RPC 客户端与事务切面；反射动态查找又带来性能损耗与类型退化。

`team4u-flow` 以**轻量级、强类型的 Flow**回应上述痛点：

- **Bean 是一等公民**：DSL 原生支持以 `Class` 与限定符声明式编排，编译期解析绑定容器单例，运行期零反射，Spring 动态代理与事务切面原样保留。
- **一个不可变定义，两种执行器**：逻辑 `Flow<I, O>` 只描述结构、本身不可执行；同一份定义既可投影为 `Local` 同步执行器，也可投影为 `Durable` 持久化执行器，无需改写业务代码。
- **四态 Outcome 类型化结果**：`Accepted`（携值成功）/ `Rejected`（业务拒绝）/ `Skipped`（弃权）/ `Failed`（执行失败）严格闭集，仅 `Accepted` 携带输出，分支语义不再依赖布尔与异常约定。
- **可选步骤保留真实 Skipped 语义**：`thenOptional` 声明"当前节点不适用时保留原值继续"；节点仍返回并上报 `Skipped`，无需用 `Accepted(input)` 伪装未处理。
- **零 Lambda 序列化 + 确定性幂等键**：Durable 快照只保存框架元数据与编码后的 `StoredValue` 槽位，绝不序列化代码、Operation 实例或 Lambda；每个节点注入 `flowId:flowVersion:executionId:path` 格式的 `invocationId`，在重试与崩溃恢复重放中保持稳定，直击外部副作用幂等难题。

---

# 复杂度分层地图

flow 的能力按三层组织，**每层独立成立**：你可以停在任意一层——停在 L1 不是逃课，而是组件设计支持的正常用法。

### L1 日常层 —— 类型化流水线

定位：**只用这一层，它就是一个类型安全的 pipeline 库——没有并发模型、没有持久化协议的心智负担。** 通过 `Flow.step / then / thenOptional / route / firstApplicable` 与四态 `Outcome` 组装业务流，`Local.compile(flow).run(input)` 同步拿结果，覆盖约 90% 场景。涉及模块：team4u-flow（core 子集）。入口：[快速开始](quick-start.md)、[Bean 容器集成](flow-bean.md)。

### L2 进阶层 —— 编排能力全开

定位：**只有用到 parallel / await / 异步入口，才需要读线程模型合同。** 在 L1 之上开启 `parallel().join()`、`await / resume`、`runAsync`、`retry / timeout`、`policy / persistentPolicy`、`recoverWith`、`scope / use` 等编排能力，并接触 `FlowResult` 三态全貌（Suspended / Cancelled）。涉及模块：team4u-flow（全量）。入口：[核心语义与机制](flow-semantics.md)。

### L3 引擎层 —— 可恢复执行器（独立组件）

定位：**team4u-flow-durable 是独立于核心的持久化执行器组件——同一份 Flow 定义，换一个执行器，获得崩溃恢复能力。** 类比 kv 之于 kv-store-jdbc：按需引入，core 不变。解决跨进程长事务、崩溃后续跑、定时唤醒等场景。涉及模块：team4u-flow-durable。入口：[Durable 持久化执行](flow-durable.md)。

| 层 | 定位 | 典型场景 | 新增概念数 | 文档入口 |
| :--- | :--- | :--- | :--- | :--- |
| L1 日常层 | 类型化流水线 | 校验、转换、路由、降级链等同步业务流 | 8 个 | [quick-start.md](quick-start.md) |
| L2 进阶层 | 编排能力全开 | 并行分派、挂起审批、限流熔断、超时重试 | +6 组 | [flow-semantics.md](flow-semantics.md) |
| L3 引擎层 | 可恢复执行器（独立组件） | 跨进程长事务、崩溃恢复、定时唤醒续跑 | +10 个 | [flow-durable.md](flow-durable.md) |

# 设计

## 核心架构

```mermaid
graph TD
    subgraph "定义期 (Definition)"
        D["Flow&lt;I, O&gt;<br/>不可变逻辑定义（纯结构，不可执行）"]
    end

    subgraph "双投影 (Dual Projection)"
        D -->|"describe(flowId)"| PD["FlowDescription<br/>冻结只读描述模型（无回调实例）"]
        D -->|"project(resolver, visitor)"| PE["ExecutableFlowVisitor&lt;R&gt;<br/>可执行投影 SPI（强类型执行合同）"]
    end
    subgraph "Local 执行器 (team4u-flow)"
        PE --> L["Local.compile(flow) → LocalExecutable<br/>同步内核 SerialMachine"]
        L --> LR["FlowResult：Completed / Suspended / Cancelled"]
    end
    subgraph "Durable 执行器 (team4u-flow-durable)"
        PE --> DR["DurableRuntime.compile → DurableExecutable"]
        DR --> CK["节点边界 CAS 检查点<br/>（revision 乐观锁）"]
        CK <--> DS[("DurableStore<br/>load + compareAndSet")]
        CK <--> SM["StateMapper<br/>确定性编码 StoredValue"]
        DR --> DRR["DurableResult：Completed / Suspended / Active / Cancelled"]
    end
    subgraph "外围生态 (Ecosystem)"
        PD --> FG["team4u-flow-graph<br/>Mermaid / 文本树渲染"]
        PE --> FB["team4u-flow-bean<br/>BeanManager 绑定解析"]
        L --> FT["team4u-flow-test<br/>桩 / 断言 / 夹具 / 并行屏障"]
        DR --> FT
    end
```

## 核心设计特色

- **Bean 一等公民**：节点原生支持 `Class<? extends Operation>` 与可选限定符；编译期一次性解析绑定，运行期零反射，Spring 事务与切面代理完整保留。
- **一个定义，两种执行器**：Local 极速执行——`Local.compile(flow).run(input)` 同步驱动，零序列化零持久化开销，挂起、取消、并行、超时全部可用；Durable 崩溃恢复——`start(executionId, input)` 在节点边界 CAS 落检查点，重启后 `recover` 从最后提交快照续跑。
- **强类型流水线与不可变定义**：步骤输入输出严格推导（`A -> B -> C`），类型不匹配编译期报错；组合方法全部返回新 `Flow`，定义天然线程安全。`then` 只由 Accepted 推进；`thenOptional` 仅接受 `O -> O`，在该局部边界消费 Skipped 并以进入前的原值继续。
- **四态业务结果 + 三态执行结果分层**：业务层 `Outcome<T>`（Accepted / Rejected / Skipped / Failed，仅 Accepted 携值）；执行层 `FlowResult<O>`（Completed / Suspended / Cancelled）。
- **扩展点开放，运行时节点封闭**：`Operation` / `Policy` / `PersistentPolicy` / `JoinStrategy` 是扩展点；运行时计划封闭为八种节点，不提供自定义节点。
- **零运行时依赖**：核心模块仅依赖 JDK（Java 8+），Maven Enforcer 禁止引入第三方运行时依赖。

---

# 核心概念

概念按层拆分：L1 表可脱离其余两表独立读懂；L2 表是编排增强；L3 表仅作索引，协议细节见 [flow-durable.md](flow-durable.md)。

## L1 日常层：8 个概念

| 概念 | 说明 |
| :--- | :--- |
| `Flow<I, O>` | 不可变逻辑流程定义：只描述结构，需经 `Local` / `DurableRuntime` / `BeanFlows` 编译后才可执行 |
| L1 组合 DSL | `Flow.step(op)` 起步、`.then(op)` 串联（支持实例 / Class / Class+qualifier / 子流程）、`.named(label)` 装饰；静态工厂 `Flow.identity / accepted / rejected / skipped / failed` |
| 路由与候选 | `Flow.route(selector).caseOf(key, branch).otherwise(branch)`；`withoutOtherwise()` 允许整体 Skipped；`Flow.firstApplicable(f1, f2, ...)` 降级链 |
| `thenOptional` | 同类型可选步骤：Accepted 用新值继续，Skipped 保留步骤入口原值继续，Rejected / Failed 仍短路 |
| `Operation<I, O>` | 业务步骤扩展点：`(OperationContext, I) -> Outcome<O>`，同步、可复用、线程安全 |
| `Outcome<T>` | 业务四态闭集：`Accepted(value)` / `Rejected(Reason)` / `Skipped(Reason)` / `Failed(Failure)`，仅 Accepted 携带输出 |
| `Reason` / `Failure` | 稳定诊断信息：`code`（稳定业务码）+ `message` + `details`；`Reason.of(code, message)` / `Failure.of(code, message)` |
| `Local` 执行 | `Local.compile(flow).run(input)` 同步返回 `FlowResult`；L1 场景下它就是 `Completed(outcome)`——携带最终四态结果（Suspended / Cancelled 仅在使用 L2 能力时出现） |

## L2 进阶层：编排增强

| 概念 | 说明 |
| :--- | :--- |
| `FlowResult<O>` 三态全貌 | `Completed(outcome)` / `Suspended(suspension)` / `Cancelled(executionId)` |
| `Suspension<O>` | Local 挂起续接句柄：不透明、单次消费，仅可由产生它的 `LocalExecutable` 恢复 |
| `ResumePoint<R>` | 类型化挂起点标识：`name` 在同一 Flow 内唯一；恢复信号类型 `R` 由其承载 |
| `Cancellation` | 协作式取消令牌：CAS 置位 + 中断绑定线程 + 父子级联；Parallel 采用 true wait-all 退出保证 |
| `parallel` / `JoinStrategy` | `Flow.parallel(Branch...).join(strategy)` 真并发分支，wait-all 后由 `JoinStrategy<O>`（`(ParallelResults) -> Outcome<O>`）显式合并 |
| `Policy<K>` | 无状态可重放网关扩展点：`before -> Gate(Proceed/Reject/Fail)`，`after` 默认空实现 |
| `PersistentPolicy<K, S>` | 状态由框架持久化的控制策略：before 闭集 `Proceed / WaitUntil / Reject / Fail`，after 闭集 `Return / RetryAt` |
| 编排增强 API | `await(ResumePoint)`、`runAsync`、`retry(Retry)`、`timeout(Duration)`、`recoverWith(Flow<Recovery<I>, O>)`、`scope / use` |
| 后四种节点 | 八节点中 `PARALLEL / AWAIT / CONTROL / COMPLETE` 随本层进入视野（前四种 `INVOKE / SEQUENCE / ROUTE / FALLBACK` L1 已见） |
| 线程模型 | Dispatcher vs Worker 双线程模型与死锁防御（嵌套 parallel / 分支内 timeout / 非 ForkJoin worker 场景） |

## L3 引擎层：概念索引

| 概念 | 说明 |
| :--- | :--- |
| `DurableRuntime` | 持久化运行时：`builder(DurableStore)` 配置 stateMapper / resolver / observer，`compile(flow, flowId, flowVersion)` 产出执行器 |
| `DurableExecutable<I, O>` | 绑定 `(flowId, flowVersion)` 的可恢复执行入口：`start / resume / recover / cancel / snapshot`（另有 `startAsync / resumeAsync`） |
| `DurableStore` | 快照存储 SPI：`load(executionId)` + `compareAndSet(executionId, expectedRevision, update)`；`expectedRevision = -1` 表示不存在才创建；内置 `InMemoryDurableStore` |
| `DurableSnapshot` | 快照信封：仅框架元数据（lifecycle、revision、frame 元数据）+ 编码后的 `StoredValue` 槽 |
| `StateMapper` / `StoredValue` | 应用状态编解码 SPI（`encode / decode`），须满足确定性契约（同一值多次编码结果 `equals` 相等）；`DefaultStateMapper` 仅支持标量 / `byte[]` / `Instant` |
| `invocationId` | 节点幂等键，格式 `flowId:flowVersion:executionId:path`；重试与恢复重放中保持稳定 |
| revision CAS 与恢复语义 | 节点边界乐观锁提交；重启后 `recover(executionId)` 从最后提交快照续跑；at-least-once 重放、resume 两段提交（恢复信号先独立落库再驱动续接）、`wakeAt` 外部调度、`PersistentPolicy` 状态随快照持久化 |
| `DurableResult<O>` | Durable 命令结果闭集：`Completed(outcome)` / `Suspended(resumePoint)` / `Active(wakeAt)` / `Cancelled` |
| 版本兼容 | `(flowId, flowVersion)` 是结构兼容边界，结构变更需递增版本 |

> **提示**：节点 `path`（如 `$/1/selector`）用于单次编译产物内的观测、断言与图渲染定位，框架不承诺 path 在 Flow 结构变更或跨版本间保持稳定，不要将其持久化或用于跨版本比对。

---

# 模块划分

| Maven 坐标 (`com.team4u`) | 定位 | 依赖 |
| :--- | :--- | :--- |
| `team4u-flow` | 核心：类型化 Outcome/Flow DSL、sealed 运行时计划、同步内核、Local 投影、Policy/并行/挂起/观察者 | 仅 JDK |
| `team4u-flow-durable` | Durable 投影：快照信封、StateMapper、revision CAS 检查点与恢复 | `team4u-flow` |
| `team4u-flow-graph` | 结构渲染：`FlowGraphs.mermaid()/text().render(FlowDescription)` | `team4u-flow`（仅静态描述面） |
| `team4u-flow-bean` | 容器绑定：`BeanFlows.compile` / `BeanOperationResolver`，从 `BeanManager` 解析 class+qualifier 绑定，代理原样保留 | `team4u-flow`、`team4u-bean` |
| `team4u-flow-test` | 测试支持：`OperationStub`、`PolicyStub`、`TraceCollector`、`FlowAssertions`、Local/Durable 夹具、`ParallelBarrier` | `team4u-flow`、`team4u-flow-durable`、JUnit |

依赖始终保持单向：durable 依赖 core；graph/bean/test 只依赖各自声明的下层模块，绝不反向依赖。

## 组件位置

```text
modules/flow
├── core    (com.team4u:team4u-flow, 包 com.team4u.framework.flow)
│   ├── Flow.java                # 不可变类型化 DSL（step/then/thenOptional/use/route/parallel/await/policy/retry/timeout）
│   ├── model/                   # Outcome 四态、FlowResult 三态、Reason/Failure、Cancellation、Suspension、诊断码
│   ├── Local.java / LocalExecutable.java   # Local 投影入口；同步/异步 run 与 resume
│   ├── api/                     # 扩展点：Operation、Policy、PersistentPolicy、JoinStrategy、ResumePoint、OperationContext
│   ├── compiler/                # Compiler 结构校验 + sealed PlanNode（八节点）
│   ├── engine/                  # SerialMachine 单同步内核（一帧栈一驱动）、ParallelRunner、取消协调
│   ├── spi/                     # OperationResolver、ExecutableFlowVisitor、ControlKind
│   └── desc/                    # FlowDescription 只读描述模型
├── durable (com.team4u:team4u-flow-durable, 包 com.team4u.framework.flow.durable)
│   ├── DurableRuntime.java      # 运行时 builder + compile
│   ├── DurableExecutable.java   # start/resume/recover/cancel/snapshot/startAsync/resumeAsync
│   ├── store/                   # DurableStore（load + compareAndSet SPI）+ InMemoryDurableStore
│   ├── snapshot/                # DurableSnapshot 信封、StateMapper 编解码（含 DefaultStateMapper）、StoredValue
│   └── engine/                  # DurableMachine、检查点与恢复、还原校验
├── graph   (com.team4u:team4u-flow-graph, 包 com.team4u.framework.flow.graph)
│   └── FlowGraphs + Mermaid / Text 渲染器   # mermaid() / text() 工厂；六通道 Mermaid、紧凑文本树
├── bean    (com.team4u:team4u-flow-bean, 包 com.team4u.framework.flow.bean)
│   └── BeanFlows / BeanOperationResolver    # BeanManager 编译入口；class+qualifier 绑定解析（代理原样）
└── test    (com.team4u:team4u-flow-test, 包 com.team4u.framework.flow.test)
    ├── OperationStub / PolicyStub      # 四态桩与调用记录
    ├── TraceCollector                  # 事件轨迹收集
    ├── FlowAssertions                  # 四态/三态/Durable 断言
    ├── LocalFixture / DurableFixture   # 执行夹具
    └── ParallelBarrier                 # 并行重叠验证屏障
```

---

# 组件联动

1. **定义**：以 `Flow.step(...).then(...)`、`thenOptional(...)` 等组合方法构建 `Flow<I, O>`；支持直接传入 Lambda 实例或 `Class` / `qualifier` 容器绑定；定义不可变、可复用、线程安全。
2. **结构投影**：`flow.describe(flowId)` 导出 `FlowDescription`，交给 graph 模块渲染 Mermaid/文本图；描述面不含任何回调实例。
3. **可执行投影**：`flow.project(resolver, visitor)`（或 `Local.compile` / `DurableRuntime.compile` 内部调用）先经 `Compiler` 校验结构、解析 class+qualifier 绑定，再经 `ExecutableFlowVisitor` 导出强类型执行计划。
4. **Local 执行**：`LocalExecutable.run(input)` 同步返回 `FlowResult`；`await` 挂起返回 `Suspended`，用 `resume(suspension, point, signal)` 续接（Suspension 单次消费）；并行分支由线程池真并发执行，wait-all 后交给 `JoinStrategy`。
5. **Durable 执行**：`DurableExecutable.start(executionId, input)` 逐节点 CAS 提交检查点；挂起返回 `Suspended`，`resume(executionId, pointName, signal)` 先把信号独立落库再驱动续接；进程重启后 `recover(executionId)` 从最后提交快照继续。
6. **观测**：`FlowObserver` 收集同步执行事件（Local 与 Durable 通用），`DurableObserver` 额外接收检查点提交/恢复/信号落库事件；test 模块的 `TraceCollector` 即其开箱实现。
7. **容器集成**：bean 模块以 `BeanFlows.compile(flow)` / `BeanFlows.compile(flow, beanManager)` 把绑定解析交给容器，Spring 场景下代理原样保留、不拆包，执行期直接调用单例。

# 文档导航

按层分组；每篇正文首行有层级徽章标注前置与所属模块。

**起点（L1 → L2 → L3 递进）**

- [快速开始](quick-start.md)：依赖引入、纯 Java 与容器绑定两种模式、四态、route / parallel / await、Local vs Durable、图渲染与第一个测试。

**L1 日常层**

- [Bean 容器集成](flow-bean.md)：Bean 声明式绑定、编译期解析与诊断、Spring 代理与事务切面保留。

**L2 进阶层**

- [核心语义与机制](flow-semantics.md)：四态传播规则、Skipped 三个消费位置、八节点语义、Policy/重试/超时、取消合同、线程模型与死锁防御、诊断码全集。

**L3 引擎层**

- [Durable 持久化执行](flow-durable.md)：快照边界、revision CAS、resume 两段提交、StateMapper 确定性契约、PersistentPolicy 状态持久化、版本兼容与恢复。

**跨层参考与实战**

- [扩展机制与 SPI](flow-extension.md)：扩展点清单与双投影 SPI（可执行合同 vs 纯描述）。
- [可视化与图表渲染](flow-graph.md)：FlowDescription 投影、六通道渲染、取消不进 join、opaque 路由键与配置摘要。
- [测试支持与断言](flow-test.md)：testkit 全 API——桩、轨迹、断言、Local/Durable 夹具、并行屏障。
- [实战案例](flow-sample.md)：订单风控路由降级、支付审批挂起恢复与电商履约实战。
