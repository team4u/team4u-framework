# 核心语义与机制

本章详细说明 `team4u-flow` 的节点行为、结果模型、上下文传递与拦截观测机制。

---

# 1. 节点语义

### 1.1 业务转换节点 (`step`)
- **定义**：将当前值类型 `C` 转换为新类型 `O`。
- **重载**：支持普通 `Step<C, O>` 与接收上下文的 `Step.Contextual<C, O>`。
- **校验**：返回 `null` 在运行时会被严格拒绝并判定为 `FAILED`（避免在强类型流水线中引入隐式空指针）。

### 1.2 副作用透传节点 (`tap`)
- **定义**：执行外部副作用操作（如记录日志、修改共享上下文），原样透传当前值类型。
- **重载**：支持普通 `Action<C>` 与上下文型 `Action.Contextual<C>`。

### 1.3 业务守卫节点 (`guard`)
- **定义**：评估 `Condition<C>`。
  - 为 `true` 时：继续执行下一节点；
  - 为 `false` 时：惰性调用 `reasonFactory` 生成 `StopReason`，流程立即正常停止（`STOPPED`），**不执行后续节点，也不触发 `recover`**。

### 1.4 条件分支节点 (`choose`)
- **定义**：通过 `selector` 提取分支键，执行命中分支的 `Flow`。
- **推导与结束**：通过 `when(key, flow)` 声明分支，支持 `otherwise(fallbackFlow)` 与 `otherwiseStop(reasonFactory)`，最终调用 `end()` 返回父 Builder。
- **未命中规则**：若 `selector` 产生未声明的键且未配置 `otherwise` / `otherwiseStop`，流程在当前 choose 节点判定为 `FAILED`（抛出 `NoSuchElementException`）。

### 1.5 组合子流程 (`then`)
- **定义**：直接内联挂载另一个 `Flow<C, O>` 作为子流程执行。
- **轨迹层级**：执行轨迹（`FlowTrace`）中完整保留父子层级关系，便于观测与回溯。

### 1.6 失败恢复 (`recover`)
- **定义**：当 body 节点抛出异常产生技术 `FAILED` 时触发。
- **状态流转**：
  - `recover` 成功返回 `FlowResult.succeeded(value)` 时，流程转为成功；
  - `recover` 返回 `FlowResult.stopped(reason)` 时，流程转为 `STOPPED`；
  - `recover` 自身抛出异常时：新异常为主因，原异常通过 `t.addSuppressed(originalCause)` 合并，流程保持 `FAILED`。

### 1.7 终态动作 (`ensure`)
- **定义**：无论 body/recover 最终是 `SUCCEEDED`、`STOPPED` 还是 `FAILED`，`ensure` 均严格执行一次。
- **异常合并规则**：
  - 若此前为 `SUCCEEDED` 或 `STOPPED`，`ensure` 抛出异常将使流程转为 `FAILED`；
  - 若此前已是 `FAILED`，原失败原因为主因，`ensure` 异常通过 `addSuppressed` 附加到原异常上。

---

# 2. 三态结果模型 (`FlowResult`)

| 状态类型 | 产生原因 | 是否触发 recover | 对 `call()` 的表现 |
|:---|:---|:---|:---|
| **`SUCCEEDED`** | 所有节点正常完成 | 否 | 正常解包并返回产物对象 |
| **`STOPPED`** | `guard` 未通过或 `choose` 命中 `otherwiseStop` | **否**（业务正常终止） | 抛出 `FlowRunException`，携带 `StopReason` |
| **`FAILED`** | 节点抛出异常、未捕获的运行时错误 | **是**（若定义了 `recover`） | 抛出 `FlowRunException`，其 `getCause()` 为原异常 |

---

# 3. 执行上下文与幂等键 (`StepContext`)

在上下文型 Step / Tap / Recover / Ensure 中，框架注入不可变的 `StepContext`：

| 属性 | 说明 | 示例 |
|:---|:---|:---|
| `flowId()` | 流程全局标识 | `checkout` |
| `executionId()` | 单次执行标识（未传时惰性自动生成） | `exec-order-1001` |
| `nodeId()` | 当前节点标识 | `reserve-stock` |
| `nodePath()` | 当前节点诊断路径（含子流程前缀） | `checkout/sub-pay/reserve-stock` |
| **`invocationId()`** | **确定性节点调用键（幂等键）** | `exec-order-1001#/s1:reserve-stock` |

> **幂等调用建议**：在执行 RPC 扣款、库存预占等不可重复的外部调用时，直接传递 `stepContext.invocationId()` 作为下游系统的业务幂等号。无论是本地重试还是 Durable 崩溃恢复重放，同一次执行中同一节点的 `invocationId` 保持绝对一致。

---

# 4. 拦截器与观察者

### 4.1 步骤拦截器 (`StepInterceptor`)
采用经典的责任链模式，可作用于整个作用域或单个节点：

```java
Flow<String, String> flow = Flows.<String>begin("demo")
        .interceptor(new StepInterceptor() {
            @Override
            public <I, O> O intercept(Chain<I, O> chain) throws Exception {
                long start = System.currentTimeMillis();
                try {
                    return chain.proceed(chain.input());
                } finally {
                    long cost = System.currentTimeMillis() - start;
                    System.out.println("Node " + chain.context().nodeId() + " took " + cost + "ms");
                }
            }
        })
        .step("s1", in -> in + "-1")
        .build();
```

### 4.2 事件观察者 (`FlowObserver`)
通过 `RunOptions.observer(observer)` 传入，监听节点与流程的开始、成功、停止与失败事件。**Observer 抛出的任何异常都会被框架隔离，绝不影响主流程执行结果**。
