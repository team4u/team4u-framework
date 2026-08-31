# 可视化图表渲染与双投影架构

`team4u-flow-diagram` 负责将流程结构渲染为直观的 Mermaid 流程图与紧凑文本树，适用于架构评审、开发文档自动化生成与日志排障。

渲染器仅消费由 `flow.describe(flowId)` 导出的只读结构模型 **`FlowDescription`**，不触及任何业务回调实例或执行状态，因此可在任何环境下安全调用而绝无副作用。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-diagram</artifactId>
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
        FD --> MM["MermaidFlowDiagramRenderer<br/>渲染直观清晰的 Mermaid 业务流程图"]
        FD --> TX["TextFlowDiagramRenderer<br/>渲染先序遍历紧凑文本树"]
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
import com.team4u.framework.flow.diagram.FlowDiagrams;

// 1. 导出只读描述模型
FlowDescription description = orderFlow.describe("order-fulfillment-flow");

// 2. 渲染为 Mermaid 流程图脚本
String mermaidScript = FlowDiagrams.mermaid().render(description);

// 3. 渲染为紧凑文本树
String textTree = FlowDiagrams.text().render(description);
```

---

## 端到端实战：电商订单履约流程的可视化呈现

为了直观展示 `team4u-flow-diagram` 的渲染效果，下面以一个包含 **前置拦截、动态路由、人工审批挂起、并行资源锁定、超时治理与失败降级** 的典型复杂业务流程为例，演示从 DSL 编排到实际渲染输出的全过程。

### 业务流程编排定义 (Java DSL)

```java
package com.example.order;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.diagram.FlowDiagrams;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.model.Recovery;
import com.team4u.framework.flow.model.Resumed;

import java.time.Duration;

public class OrderFulfillmentExample {

    // 声明业务步骤契约接口（亦可直接对接 Spring Bean / Qualifier）
    public interface RiskCheckOperation extends Operation<String, String> {}
    public interface RiskRouter extends Operation<String, String> {}
    public interface PassAuditOperation extends Operation<Resumed<String, String>, String> {}
    public interface LockInventoryOperation extends Operation<String, String> {}
    public interface LockCouponOperation extends Operation<String, String> {}
    public interface ChargePaymentOperation extends Operation<String, String> {}
    public interface BackupPaymentOperation extends Operation<Recovery<String>, String> {}
    public interface IssueReceiptOperation extends Operation<String, String> {}

    public static Flow<String, String> buildOrderFlow() {
        // 1. 并行分支：库存预占（带 2s 超时）与卡券锁定并行执行
        Branch<String, String> inventoryBranch = Branch.of("lock-inventory",
                Flow.<String, String>step(LockInventoryOperation.class, "stock-service")
                        .timeout(Duration.ofSeconds(2))
                        .named("库存预占"));

        Branch<String, String> couponBranch = Branch.of("lock-coupon",
                Flow.<String, String>step(LockCouponOperation.class, "coupon-service")
                        .named("卡券锁定"));

        Flow<String, String> parallelLock = Flow.parallel(inventoryBranch, couponBranch)
                .join(results -> Outcome.accepted("resources-locked"))
                .named("并行资源锁定");

        // 2. 人工审批挂起子流程：await 挂起等待外部审批信号注入后唤醒推进
        Flow<String, String> manualAuditFlow = Flow.<String>identity()
                .await(ResumePoint.<String>named("manual-audit"))
                .then(Flow.step(PassAuditOperation.class, "audit-handler"))
                .named("高风险人工审核");

        // 3. 风控动态路由：LOW 直通，HIGH 挂起人工审批，其它高风险直接拒绝
        Flow<String, String> riskRoute = Flow.route(RiskRouter.class, "risk-router")
                .caseOf("LOW", Flow.<String>identity().named("低风险直通"))
                .caseOf("HIGH", manualAuditFlow)
                .otherwise(Flow.<String, String>rejected(Reason.of("HIGH_RISK_REJECT", "高风险直接阻断")));

        // 4. 支付扣款步骤：主通道带 5s 超时，超时或异常自动触发 recoverWith 降级至备用通道
        Flow<String, String> paymentStep = Flow.<String, String>step(ChargePaymentOperation.class, "main-gateway")
                .named("主通道支付扣款")
                .timeout(Duration.ofSeconds(5))
                .recoverWith(Flow.<Recovery<String>, String>step(BackupPaymentOperation.class, "backup-gateway").named("备用通道降级"));

        // 5. 组装完整流水线并包裹具名 Scope 作用域边界
        return Flow.scope("order-checkout-process",
                Flow.<String, String>step(RiskCheckOperation.class, "risk-checker").named("前置风控拦截")
                        .then(riskRoute)
                        .then(parallelLock)
                        .then(paymentStep)
                        .then(Flow.<String, String>step(IssueReceiptOperation.class, "receipt-service").named("生成出货单据"))
        ).named("order-fulfillment-flow");
    }

    public static void main(String[] args) {
        Flow<String, String> orderFlow = buildOrderFlow();

        // 导出只读描述拓扑（零执行副作用）
        FlowDescription desc = orderFlow.describe("order-fulfillment-flow");

        // 渲染为 Mermaid 流程图脚本
        String mermaidDsl = FlowDiagrams.mermaid().render(desc);
        System.out.println(mermaidDsl);

        // 渲染为紧凑文本树
        String textTree = FlowDiagrams.text().render(desc);
        System.out.println(textTree);
    }
}
```

### 真实效果：Mermaid 流程图 (Live Graph)

> [!TIP]
> 下图由 `FlowDiagrams.mermaid().render(desc)` 生成的标准 Mermaid 脚本直接渲染呈现。
> 流程图直观展示了**主干推进、风控分支决策、人工审核挂起与唤醒、并行分叉与合并 Join、超时控制徽章与降级容错**，层次分明，一目了然。

```mermaid
flowchart TD
    flow_start(["开始: order-fulfillment-flow"])
    n1["前置风控拦截<br/>RiskCheckOperation (risk-checker)"]
    n2(["低风险直通 (透传)"])
    n3(["透传 (Identity)"])
    n4["挂起等待: manual-audit"]
    n5["PassAuditOperation<br/>(audit-handler)"]
    n7(["[REJECTED]"])
    n8{"RiskRouter<br/>(risk-router)"}
    n9["库存预占 [timeout: 2s]<br/>LockInventoryOperation (stock-service)"]
    n10["卡券锁定<br/>LockCouponOperation (coupon-service)"]
    n11{{"并行: 并行资源锁定"}}
    n12["合并 (Join)"]
    n13["主通道支付扣款 [timeout: 5s]<br/>ChargePaymentOperation (main-gateway)"]
    n14["备用通道降级<br/>BackupPaymentOperation (backup-gateway)"]
    n15["生成出货单据<br/>IssueReceiptOperation (receipt-service)"]
    flow_end(["结束 (ACCEPTED)"])

    subgraph sg_n6 ["高风险人工审核"]
        n3
        n4
        n5
    end
    subgraph sg_n16 ["作用域: order-checkout-process"]
        n1
        n8
        n2
        n3
        n4
        n5
        n7
        n11
        n9
        n10
        n12
        n13
        n14
        n15
    end

    n3 --> n4
    n4 --> n5
    n8 -->|LOW| n2
    n8 -->|HIGH| n3
    n8 -.->|otherwise| n7
    n11 -->|lock-inventory| n9
    n11 -->|lock-coupon| n10
    n9 --> n12
    n10 --> n12
    n13 -.->|FAILED 降级| n14
    n1 --> n8
    n2 --> n11
    n5 --> n11
    n12 --> n13
    n13 --> n15
    n14 --> n15
    flow_start --> n1
    n15 --> flow_end
```

### 真实效果：紧凑文本树 (Text Tree)

`FlowDiagrams.text().render(desc)` 生成的先序遍历文本树，每一行代表 AST 中的一个只读节点。非常适合输出在生产环境控制台日志、排障工具或 CI/CD 自动化测试断言中：

```text
flow id="order-fulfillment-flow"
path="$" kind=SEQUENCE label="order-fulfillment-flow" scope="order-checkout-process" children=1
path="$/0" kind=SEQUENCE label=<none> scope=<none> children=5
path="$/0/0" kind=INVOKE label="前置风控拦截" binding=OPERATION contract=com.example.order.RiskCheckOperation qualifier="risk-checker"
path="$/0/1" kind=ROUTE label=<none> routes=2 otherwise=branch
path="$/0/1/selector" kind=INVOKE label=<none> binding=OPERATION contract=com.example.order.RiskRouter qualifier="risk-router"
path="$/0/1/case:0" kind=COMPLETE label="低风险直通" complete=IDENTITY
path="$/0/1/case:1" kind=SEQUENCE label="高风险人工审核" scope=<none> children=3
path="$/0/1/case:1/0" kind=COMPLETE label=<none> complete=IDENTITY
path="$/0/1/case:1/1" kind=AWAIT label=<none> resume="manual-audit"
path="$/0/1/case:1/2" kind=INVOKE label=<none> binding=OPERATION contract=com.example.order.PassAuditOperation qualifier="audit-handler"
path="$/0/1/otherwise" kind=COMPLETE label=<none> complete=REJECTED
path="$/0/2" kind=PARALLEL label="并行资源锁定" branches=2 tokens=["lock-inventory","lock-coupon"] join=static
path="$/0/2/branch:0" kind=CONTROL label="库存预占" control=TIMEOUT config=timeout=2s0ns
path="$/0/2/branch:0/body" kind=INVOKE label=<none> binding=OPERATION contract=com.example.order.LockInventoryOperation qualifier="stock-service"
path="$/0/2/branch:1" kind=INVOKE label="卡券锁定" binding=OPERATION contract=com.example.order.LockCouponOperation qualifier="coupon-service"
path="$/0/3" kind=FALLBACK label=<none> trigger=FAILED branches=2
path="$/0/3/branch:0" kind=CONTROL label=<none> control=TIMEOUT config=timeout=5s0ns
path="$/0/3/branch:0/body" kind=INVOKE label="主通道支付扣款" binding=OPERATION contract=com.example.order.ChargePaymentOperation qualifier="main-gateway"
path="$/0/3/branch:1" kind=INVOKE label="备用通道降级" binding=OPERATION contract=com.example.order.BackupPaymentOperation qualifier="backup-gateway"
path="$/0/4" kind=INVOKE label="生成出货单据" binding=OPERATION contract=com.example.order.IssueReceiptOperation qualifier="receipt-service"
```

---

## Spring Boot 可视化端点实战（浏览器实时渲染 Flow 图表）

在微服务管理端或运维监控系统中，可以暴露一个轻量级的 Controller，将业务 Flow 渲染为网页可视化图表或纯文本 DSL：

```java
@RestController
@RequestMapping("/admin/flows")
public class FlowDiagramVisualizerController {

    @Autowired
    private Flow<OrderRequest, Receipt> orderFlow;

    /**
     * 1. 获取原始 Mermaid DSL 脚本接口 (供前端组件或 Docsify 渲染)
     */
    @GetMapping(value = "/order/mermaid", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getOrderFlowMermaid() {
        FlowDescription desc = orderFlow.describe("order-fulfillment-flow");
        return FlowDiagrams.mermaid().render(desc);
    }

    /**
     * 2. 直接在浏览器中查看可视化流程图 HTML 页面
     */
    @GetMapping(value = "/order/view", produces = MediaType.TEXT_HTML_VALUE)
    public String viewOrderFlowHtml() {
        FlowDescription desc = orderFlow.describe("order-fulfillment-flow");
        String mermaidDsl = FlowDiagrams.mermaid().render(desc);

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

## 流程图渲染设计规范

| 结构 / 语义 | 图形表示 | 说明 |
| :--- | :--- | :--- |
| **主干推进** | `A --> B` (实线) | 正常业务成功推进通道（Happy Path） |
| **异常 / 降级** | `A -.->|FAILED 降级| B` (虚线) | `recoverWith` 失败降级或 `otherwise` 分支 |
| **控制策略徽章** | `[timeout: 2s]`, `[policy: rate-limit]` | 超时、限流等策略直接作为纯文本属性徽章显示在步骤上，不产生冗余 AST 方块 |
| **路由决策** | 菱形 `{}` 节点 | 动态选择器与分支判定，条件清晰标注在出边上（`LOW`, `HIGH`, `otherwise`） |
| **并行分发与合并** | 六边形 `{{}}` 与 `[合并 (Join)]` | 并行分支分发与汇聚点，自动折叠底层 Wait-All 胶水网关 |
| **挂起等待** | `挂起等待: point` | 人工审批或外部信号注入点 |
| **作用域** | `subgraph` 容器框 | 具名 Scope 自动渲染为清晰的边界分组矩形 |

---

## 稳定渲染与 Opaque 机制

为了确保图表渲染的安全与确定性：
- **不透明路由键 (Opaque Keys)**：无法安全确定性序列化的路由键（如匿名类、闭包等）统一渲染为 `<opaque>` 占位符，渲染器从不主动调用其 `toString()`，防止引发副作用或泄露敏感信息；
- **确定性输出**：同一 `FlowDescription` 的渲染结果逐字节确定，无随机生成的 ID，便于进行自动化测试断言与 Git 版本比对；
- **特殊字符转义**：节点标签中的引号、换行符与管道符自动转义为标准 HTML 实体。

---

## 紧凑文本树渲染规范

`FlowDiagrams.text()` 采用先序遍历生成单行文本树，每行包含节点的唯一路径、种类、标签与关键属性：

```text
path="$/0/1" kind=ROUTE label=<none> routes=2 otherwise=branch
path="$/0/1/selector" kind=INVOKE label=<none> binding=OPERATION contract=com.example.ChannelSelect qualifier=<none>
path="$/0/1/case:0" kind=COMPLETE label=<none> complete=ACCEPTED
path="$/0/1/case:1" kind=COMPLETE label=<none> complete=ACCEPTED
```

- `path`：AST 拓扑绝对路径（`$` 为根节点，`/0`、`/1` 为步骤索引，`/selector`、`/branch:0` 为子节点）；
- `kind`：节点元类型（`SEQUENCE`、`INVOKE`、`ROUTE`、`PARALLEL`、`FALLBACK`、`AWAIT`、`CONTROL`、`COMPLETE`）；
- `label`：通过 `.named("...")` 显式指定的业务语义标签；
- `binding`：操作绑定的契约 Class 与限定符 Qualifier。

---

## 性能与深度嵌套支持

渲染器内部基于**显式栈的迭代机制**实现，而非递归调用。面对数千层嵌套的 Scope 或 Timeout 结构，均能在线性时间内稳定渲染，不会发生 JVM 栈溢出。

---

## 关联章节与进一步阅读

- 掌握单元测试断言与测试桩：[测试支持与测试套件](flow-test.md)
- 查看完整的业务流程图渲染实战示例：[实战案例库与生产模式](flow-sample.md)
