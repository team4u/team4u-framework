# 可视化图表渲染与双投影架构

`team4u-flow-graph` 负责将流程结构渲染为标准 Mermaid 流程图与紧凑文本树，适用于架构评审、开发文档自动化生成与日志排障。

渲染器仅消费由 `flow.describe(flowId)` 导出的只读结构模型 **`FlowDescription`**，不触及任何业务回调实例或执行状态，因此可在任何环境下安全调用而绝无副作用。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-graph</artifactId>
</dependency>
```

---

## 双投影架构设计 (Dual Projection)

`Flow<I, O>` 对外提供两条职责严格隔离的投影通道：

```mermaid
graph TD
    F["Flow&lt;I, O&gt;<br/>不可变逻辑拓扑（纯结构）"]
    
    subgraph "结构描述通道 (Description Projection)"
        F -->|"flow.describe(flowId)"| FD["FlowDescription<br/>冻结只读数据模型（无回调实例、零执行副作用）"]
        FD --> MM["MermaidFlowGraphRenderer<br/>渲染标准 Mermaid 6 通道流程图"]
        FD --> TX["TextFlowGraphRenderer<br/>渲染先序遍历紧凑文本树"]
    end

    subgraph "可执行计划通道 (Executable Projection)"
        F -->|"flow.project(resolver, visitor)"| PE["ExecutableFlowVisitor&lt;R&gt;<br/>强类型执行计划编译器"]
        PE --> L["Local.compile (LocalExecutable)"]
        PE --> D["DurableRuntime.compile (DurableExecutable)"]
    end
```

---

## 渲染器门面 API

```java
import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.graph.FlowGraphs;

// 1. 导出只读描述模型
FlowDescription description = checkoutFlow.describe("checkout-flow");

// 2. 渲染为 Mermaid 流程图脚本
String mermaidScript = FlowGraphs.mermaid().render(description);

// 3. 渲染为紧凑文本树
String textTree = FlowGraphs.text().render(description);
```

---

## Spring Boot 可视化端点实战（浏览器实时渲染 Flow 图表）

在微服务管理端或运维监控系统中，可以暴露一个轻量级的 Controller，将业务 Flow 渲染为网页可视化图表或纯文本 DSL：

```java
@RestController
@RequestMapping("/admin/flows")
public class FlowGraphVisualizerController {

    @Autowired
    private Flow<OrderRequest, Receipt> orderFlow;

    /**
     * 1. 获取原始 Mermaid DSL 脚本接口 (供前端组件或 Docsify 渲染)
     */
    @GetMapping(value = "/order/mermaid", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getOrderFlowMermaid() {
        FlowDescription desc = orderFlow.describe("order-fulfillment-flow");
        return FlowGraphs.mermaid().render(desc);
    }

    /**
     * 2. 直接在浏览器中查看可视化流程图 HTML 页面
     */
    @GetMapping(value = "/order/view", produces = MediaType.TEXT_HTML_VALUE)
    public String viewOrderFlowHtml() {
        FlowDescription desc = orderFlow.describe("order-fulfillment-flow");
        String mermaidDsl = FlowGraphs.mermaid().render(desc);

        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "  <title>Flow Visualization</title>\n" +
                "  <script type=\"module\">\n" +
                "    import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs';\n" +
                "    mermaid.initialize({ startOnLoad: true, theme: 'default' });\n" +
                "  </script>\n" +
                "</head>\n" +
                "<body style=\"padding: 20px; font-family: sans-serif;\">\n" +
                "  <h2>订单履约流程拓扑图 (Live)</h2>\n" +
                "  <pre class=\"mermaid\">\n" +
                mermaidDsl + "\n" +
                "  </pre>\n" +
                "</body>\n" +
                "</html>";
    }
}
```

---

## Mermaid 六通道终结模型

Mermaid 渲染器通过**六个全局终结通道**刻画一次执行的所有可能归宿：

| 通道标识 | 对应终结点 | 含义说明 |
| :--- | :--- | :--- |
| `terminal_accepted` | `COMPLETED \| ACCEPTED` | 正常成功完成，携带业务输出 |
| `terminal_rejected` | `COMPLETED \| REJECTED` | 业务拒绝完成（预期内短路） |
| `terminal_skipped` | `COMPLETED \| SKIPPED` | 弃权跳过完成 |
| `terminal_failed` | `COMPLETED \| FAILED` | 发生技术失败或系统异常 |
| `terminal_suspended` | `SUSPENDED` | 挂起等待外部信号注入 |
| `terminal_cancelled` | `CANCELLED` | 流程被协作取消令牌终止 |

```mermaid
graph LR
    subgraph "推进与短路分离机制"
        Step1["Step 1"] -->|"ACCEPTED"| Step2["Step 2"]
        Step1 -.->|"REJECTED"| R_TERM["terminal_rejected"]
        Step1 -.->|"FAILED"| F_TERM["terminal_failed"]
        Step2 -->|"ACCEPTED"| A_TERM["terminal_accepted"]
    end
```

### 渲染规则要点
- **推进与短路分离**：Sequence 推进边仅标注推进态（`-->|ACCEPTED|`），非推进态（REJECTED / SKIPPED / FAILED）直接连接至对应的全局终结点；
- **可选步骤可视化**：`thenOptional` 描述为带有 SKIPPED 触发边的 Fallback 结构，图中直观展示 Skipped 被局部消费后经由 Identity 分支重新接入 Accepted 通道；
- **降级触发清晰**：`firstApplicable` 标注 `SKIPPED | next applicable` 边，`recoverWith` 标注 `FAILED | recover` 边；
- **取消出口绕过汇聚**：在 `parallel` 并行块中，分支若被取消（`CANCELLED`），其流向直接导向 `terminal_cancelled`，**绝不经过 wait-all 网关与 join 汇合节点**。

---

## 稳定渲染与 Opaque 机制

为了确保图表渲染的安全与确定性：
- **不透明路由键 (Opaque Keys)**：无法安全确定性序列化的路由键（如匿名类、闭包等）统一渲染为 `<opaque>` 占位符，渲染器从不主动调用其 `toString()`，防止引发副作用或泄露敏感信息；
- **确定性输出**：同一 `FlowDescription` 的渲染结果逐字节确定，无随机生成的 ID，便于进行自动化测试断言与 Git 版本比对；
- **特殊字符转义**：节点标签中的引号、换行符与管道符自动转义为标准 HTML 实体。

---

## 控制节点配置摘要

Control 节点在图表中渲染紧凑且标准化的配置摘要：

```text
control=RETRY config=maxAttempts=3,backoff=2s
control=TIMEOUT config=timeout=5s
control=POLICY config=<none>
control=PERSISTENT_POLICY config=<none>
```

---

## 紧凑文本树渲染

`FlowGraphs.text()` 采用先序遍历生成单行文本树，便于在控制台日志中输出或在单元测试中进行结构断言：

```text
flow id="checkout"
path="$" kind=SEQUENCE label=<none> scope=<none> children=2
path="$/0" kind=INVOKE label=<none> binding=OPERATION contract=com.example.RiskScan qualifier=<none>
path="$/1" kind=ROUTE label=<none> routes=2 otherwise=no-match:SKIPPED
path="$/1/selector" kind=INVOKE label=<none> binding=OPERATION contract=com.example.ChannelSelect qualifier=<none>
path="$/1/case:0" kind=COMPLETE label=<none> complete=ACCEPTED
path="$/1/case:1" kind=COMPLETE label=<none> complete=ACCEPTED
```

---

## 性能与深度嵌套支持

渲染器内部基于**显式栈的迭代机制**实现，而非递归调用。面对数千层嵌套的 Scope 或 Timeout 结构，均能在线性时间内稳定渲染，不会发生 JVM 栈溢出。

---

## 关联章节与进一步阅读

- 掌握单元测试断言与测试桩：[测试支持与测试套件](flow-test.md)
- 查看完整的业务流程图渲染实战示例：[实战案例库与生产模式](flow-sample.md)
