# 可视化图表渲染

`team4u-flow-graph` 模块提供将流程结构渲染为标准 Mermaid 流程图与 ASCII 文本树的能力。

---

# 1. 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-graph</artifactId>
</dependency>
```

---

# 2. Mermaid 流程图渲染

通过 `flow.describe()` 获取不可变的只读结构描述，再调用 `FlowGraphs.mermaid()` 渲染：

```java
FlowDescription description = checkoutFlow.describe();
String mermaidScript = FlowGraphs.mermaid().render(description);
System.out.println(mermaidScript);
```

### 渲染结果示例

```mermaid
flowchart TD
    start_1(["Start: checkout"])
    node_2_validate_order{"Guard: validate-order"}
    start_1 --> node_2_validate_order
    stop_3(["STOPPED"]).style fill:#f9f,stroke:#333
    node_2_validate_order -->|stopped| stop_3
    guard_pass_4(( ))
    node_2_validate_order -->|passed| guard_pass_4
    node_5_reserve_stock["Tap: reserve-stock"]
    guard_pass_4 --> node_5_reserve_stock
    node_6_choose_channel{"Choose: choose-channel"}
    node_5_reserve_stock --> node_6_choose_channel
    join_7(( ))
    node_8_call_card_gateway["call-card-gateway"]
    node_8_call_card_gateway --> join_7
    node_6_choose_channel -->|"CARD"| node_8_call_card_gateway
    node_9_call_wallet_gateway["call-wallet-gateway"]
    node_9_call_wallet_gateway --> join_7
    node_6_choose_channel -->|"WALLET"| node_9_call_wallet_gateway
    node_10_build_receipt["Step: build-receipt"]
    join_7 --> node_10_build_receipt
    node_11_fallback["Recover: fallback"]
    node_10_build_receipt -.->|on failure| node_11_fallback
    node_12_cleanup_metrics["Ensure: cleanup-metrics"]
    node_11_fallback --> node_12_cleanup_metrics
    end_13(["End: checkout"])
    node_12_cleanup_metrics --> end_13
```

---

# 3. ASCII 文本树渲染

适合在启动日志、控制台或命令行工具中快速打印流程拓扑：

```java
String textTree = FlowGraphs.text().render(checkoutFlow.describe());
System.out.println(textTree);
```

### 渲染结果示例

```text
Flow: checkout
├── GUARD: validate-order
├── TAP: reserve-stock
├── CHOOSE: choose-channel
│   ├── [CARD]
│   │   └── TAP: call-card-gateway
│   ├── [WALLET]
│   │   └── TAP: call-wallet-gateway
│   └── [otherwise -> STOPPED]
├── STEP: build-receipt
├── RECOVER: fallback
└── ENSURE: cleanup-metrics
```
