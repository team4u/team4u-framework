# 路由诊断与 Trace

在微服务与复杂业务架构中，当路由结果不符合预期（如未命中预期分支而进入了兜底）时，黑盒引擎通常难以定位问题。`team4u-router` 提供了白盒诊断能力，通过 `trace` 接口能够清晰展示每条规则的评估细节、底层 AST 计算树、计算耗时及短路原因。

---

## 快速使用 Trace

`RoutingManager` 提供了专门的 `trace`、`traceByConfig` 与 `traceByPolicy` 接口：

```java
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.trace.RouteTrace;
import com.team4u.framework.router.api.trace.RouteTraceEvent;
import com.team4u.framework.router.api.trace.RuleTrace;

// 执行带白盒诊断的路由计算
RouteTrace<String> trace = RoutingManager.global().trace("order-router", requestContext);

// 1. 获取基础摘要信息
System.out.println("路由器类型: " + trace.getRouterType()); // 如 "expression"
System.out.println("路由总耗时: " + trace.getCostMs() + "ms");
System.out.println("最终命中状态: " + trace.getResult().getOutcome()); // 如 RULE_MATCH
System.out.println("最终结果: " + trace.getResult().getValue());

// 2. 遍历规则评估步骤详情
for (RuleTrace step : trace.getSteps()) {
    System.out.printf("规则条件: %s | 是否匹配: %b | 是否兜底: %b%n",
            step.getCondition(),
            step.isMatched(),
            step.isFallback());
            
    // 打印底层详细诊断信息
    if (step.getDiagnosticDetail() != null) {
        System.out.println("  └─ 诊断细节: " + step.getDiagnosticDetail());
    }
}

// 3. 遍历拦截器观察事件
for (RouteTraceEvent event : trace.getEvents()) {
    System.out.printf("拦截器: %s | 阶段: %s | 详情: %s%n",
            event.getSource(), event.getPhase(), event.getDetail());
}
```

---

## Trace 数据结构详解

### 1. `RouteTrace<T>`

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `routerType` | `String` | 实际执行的路由器类型（`map` / `expression` / `weight` / `composite`） |
| `costMs` | `long` | 路由规则评估总耗时（毫秒） |
| `result` | `RouteResult<T>` | 最终产出的路由结果对象（包含 `outcome` 与 `value`） |
| `steps` | `List<RuleTrace>` | 规则评估步骤明细列表，按规则遍历顺序记录 |
| `events` | `List<RouteTraceEvent>` | 由 `TraceableRouteInterceptor` 捕获的观察事件列表 |

### 2. `RuleTrace`

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `condition` | `String` | 当前评估的原始条件（表达式、映射 Key、权重数值或兜底标记 `FALLBACK`） |
| `matched` | `boolean` | 当前规则是否评估为命中 |
| `isFallback` | `boolean` | 是否为兜底分支 |
| `diagnosticDetail` | `Object` | 底层引擎输出的详细诊断（如 Criterion AST 树状计算值、Weight 命中的 Hash 区间、Composite 子路由 Trace 对象） |

### 3. `RouteTraceEvent`

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `source` | `String` | 产生事件的拦截器类名 |
| `phase` | `String` | 事件发生阶段（`before` / `after` / `before-error` / `after-error`） |
| `detail` | `Object` | 观察事件的上下文详情或异常信息 |

---

## 各类路由器的 Trace 输出特性

### 1. ExpressionRouter 的 Trace 输出
深度集成 `team4u-criterion` 的 `TraceNode`，将表达式树的每个叶子节点与实际值直观呈现：

```text
规则条件: region == 'CN' && amount > 5000 | 是否匹配: false | 是否兜底: false
  └─ 诊断细节: (region == 'CN' {"CN"}[Y] AND amount > 5000 {2000}[N])[N]
规则条件: region == 'CN' && amount > 1000 | 是否匹配: true  | 是否兜底: false
  └─ 诊断细节: (region == 'CN' {"CN"}[Y] AND amount > 1000 {2000}[Y])[Y]
```
> [!TIP]
> 诊断细节一眼即可看出：第一条规则中 `region == 'CN'` 满足 (`[Y]`)，但 `amount > 5000` 实际值为 `2000` 不满足 (`[N]`)，从而导致逻辑短路。

### 2. WeightRouter 的 Trace 输出
展示分流键计算出的 MurmurHash32 结果与所落入的权重区间：

```text
规则条件: 30 | 是否匹配: true | 是否兜底: false
  └─ 诊断细节: hash=1523, range=[20, 50)
```

### 3. CompositeRouter 的 Trace 输出
以嵌套形式逐层记录子路由的 `RouteTrace` 执行细节：

```text
规则条件: translator.biz-live | 是否匹配: false | 是否兜底: false
  └─ 诊断细节: RouteTrace(routerType=map, steps=[...], result=RouteResult(outcome=NO_MATCH))
规则条件: translator.common   | 是否匹配: true  | 是否兜底: false
  └─ 诊断细节: RouteTrace(routerType=expression, steps=[...], result=RouteResult(outcome=RULE_MATCH))
```

### 4. MapRouter 的 Trace 输出
未命中时展示规则键对比与兜底进入过程：

```text
规则条件: WECHAT_PAY | 是否匹配: false | 是否兜底: false
规则条件: FALLBACK   | 是否匹配: true  | 是否兜底: true
```

---

## 生产级最佳实践

- **线上采样 Trace**：高频流量下，通过 HTTP Header（如 `X-Debug-Trace: true`）或指定灰度用户开启 `trace`，其余流量走常规 `route()` 以保持最高性能。
- **结合日志与监控**：在拦截器中将 `trace.getCostMs()` 与未命中报警联动输出，辅助排查规则下发异常。
