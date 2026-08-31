# 可视化与图表渲染

> 层级：工具 · 全层 · 模块：team4u-flow-graph

`team4u-flow-graph` 把流程结构渲染为标准 Mermaid 流程图与紧凑文本树，用于评审、文档与日志。

graph 只消费 `flow.describe(flowId)` 导出的 **FlowDescription**——冻结的只读结构描述模型，不含任何回调实例、业务值或执行状态。因此它对任何层的 Flow 都可用：L1 类型化流水线、L2 编排（parallel / await / retry / policy）与 L3 Durable 的同一份定义共享同一渲染入口；描述面与执行面彻底隔离，渲染永远不会触发副作用。

---

# 1. 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-graph</artifactId>
</dependency>
```

---

# 2. 两个渲染器

```java
import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.graph.FlowGraphs;

FlowDescription description = checkoutFlow.describe("checkout");

String mermaidScript = FlowGraphs.mermaid().render(description); // Mermaid 流程图
String textTree = FlowGraphs.text().render(description);        // 紧凑文本树
```

graph 模块的编译期依赖仅限 `FlowDescription`、`NodeDescription`、`BindingDescriptor`、`Retry` 四个纯描述类型，不触及 `Flow`、`ExecutableFlowVisitor` 或任何可执行回调（该边界由测试守护）。八种节点 Kind（INVOKE / SEQUENCE / ROUTE / FALLBACK / PARALLEL / AWAIT / CONTROL / COMPLETE）全部可稳定渲染。

---

# 3. Mermaid：六通道模型

Mermaid 输出以**六个终结通道**刻画一次执行的全部可能归宿：

| 通道 | 终结点 | 含义 |
| :--- | :--- | :--- |
| `terminal_accepted` | `COMPLETED \| ACCEPTED` | 正常成功完成 |
| `terminal_rejected` | `COMPLETED \| REJECTED` | 业务拒绝完成 |
| `terminal_skipped` | `COMPLETED \| SKIPPED` | 弃权/跳过完成 |
| `terminal_failed` | `COMPLETED \| FAILED` | 失败完成 |
| `terminal_suspended` | `SUSPENDED` | 挂起等待外部信号 |
| `terminal_cancelled` | `CANCELLED` | 执行被取消 |

要点：

- 四个业务通道与两个生命周期通道（挂起/取消）互不混淆；只有 Accepted 通道表达"携带输出成功"。
- Sequence 边上只标注推进通道（`-->|ACCEPTED|`），非推进态（REJECTED/SKIPPED/FAILED）直接流向对应终结点，可视化地呈现"then 仅 Accepted 推进"。
- `thenOptional` 不增加第九种节点：它描述为 `FALLBACK(trigger=SKIPPED)`，包含可选步骤与 `COMPLETE(identity)` 两个分支。图中可直接看到 Skipped 被局部消费后经 identity 回到 Accepted 通道。
- 降级节点只渲染其配置的触发器：`firstApplicable` 和 `thenOptional` 渲染 SKIPPED 触发边，`recoverWith` 渲染 `FAILED | recover`，不会混入其他通道。
- Route 渲染 `case=<key>` 分支、`otherwise` 与 `NO MATCH | SKIPPED`（`withoutOtherwise` 时）。

---

# 4. 取消不进 join

Parallel 渲染显式声明"取消出口绕过汇合"的合同：

- 每个分支的业务完成（含四态）汇入 `WAIT ALL | branches=N`，再进入 `JOIN | static outcome contract`。
- 分支被取消（CANCELLED）或挂起（SUSPENDED）的出口**直接流向对应终结点，不经过 wait-all 与 join**——JoinStrategy 永远不会收到被取消分支的结果，与运行时 true wait-all 合同一致。
- Parallel 自身被取消经由 `CANCEL` 节点直达 `terminal_cancelled`，起点也有一条常量取消边（`flow_start -.->|CANCELLED| terminal_cancelled`）。

---

# 5. opaque：不可稳定呈现的值

描述面刻意不泄露无法稳定、安全呈现的值：

- **opaque 路由键**：非 final 类型、lambda、代理等无法确定性序列化的路由键渲染为固定占位符 `<opaque>`，渲染器**从不调用其 `toString()`**（对会泄露秘密或产生副作用的 key 安全）；`String`、装箱基本类型、`BigInteger` / `BigDecimal` 与枚举按精确值渲染。
- **不含业务常量**：COMPLETE 节点渲染 `complete=ACCEPTED/REJECTED/...` 但不携带值本体；文本渲染不出现 `implementation` 与路由 case 的业务值。
- **元数据安全转义**：hostile 标签（引号、管道、换行等）在 Mermaid 中转义为 HTML 实体（`&#124;`、`&quot;` 等），在文本中转义为可读转义序列，保证一行一节点。
- **确定性**：同一 FlowDescription 的渲染结果逐字节确定（无随机 id、无内存地址），重复标签自动生成无冲突节点 id。

---

# 6. 配置摘要

Control 节点渲染**配置摘要而非配置对象**，两种渲染器格式一致且稳定：

```text
control=RETRY config=maxAttempts=7,backoff=11s500ns
control=TIMEOUT config=timeout=29s25ns
control=POLICY config=<none>
control=PERSISTENT_POLICY config=<none>
```

- `Retry` 摘要为 `maxAttempts=N,backoff=<秒><纳秒>s/ns 紧凑格式`（不是 `Duration.toString()` 的 `PT11S` 风格）。
- `TIMEOUT` 摘要为 `timeout=<紧凑时长>`。
- Policy 类 Control 只渲染绑定契约与 qualifier，无配置摘要（`<none>`）。

---

# 7. 文本树

`FlowGraphs.text()` 输出紧凑文本树：先序遍历、一行一个节点（无缩进），包含 `path`、`kind`、`label`、绑定契约（`binding=... contract=... qualifier=...`）、scope 名与 Control 配置摘要：

```text
flow id="checkout"
path="$" kind=SEQUENCE label=<none> scope=<none> children=2
path="$/0" kind=INVOKE label=<none> binding=OPERATION contract=com.example.RiskScan qualifier=<none>
path="$/1" kind=ROUTE label=<none> routes=2 otherwise=no-match:SKIPPED
path="$/1/selector" kind=INVOKE label=<none> binding=OPERATION contract=com.example.ChannelSelect qualifier=<none>
path="$/1/case:0" kind=COMPLETE label=<none> complete=ACCEPTED
path="$/1/case:1" kind=COMPLETE label=<none> complete=ACCEPTED
```

适合日志、单测断言与代码评审。注意节点 `path` 仅用于单次产物的观测定位，框架不承诺 path 跨 Flow 结构变更稳定（见[核心语义](flow-semantics.md)）。

---

# 8. 规模与性能

两个渲染器均为迭代式（显式栈、禁止递归）实现：5000 层嵌套 scope / 5000 层嵌套 timeout 均在线性成本内完成渲染，不触发栈溢出。渲染复杂流程前无需手动展平。
