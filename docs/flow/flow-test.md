# 测试支持与断言

`team4u-flow-test` 提供了专门针对 `team4u-flow` 的测试桩对象（Stub）与流畅的链式断言工具（Assert）。

---

# 1. 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-test</artifactId>
    <scope>test</scope>
</dependency>
```

---

# 2. 测试桩对象 (Stubs)

桩对象用于在单元测试中模拟步骤执行、捕获入参、记录调用次数或模拟异常：

### 2.1 步骤桩 (`StepStub`)
```java
// 1. 固定返回值桩
StepStub<Integer, Integer> doubler = StepStub.function(in -> in * 2);

// 2. 异常模拟桩
StepStub<Integer, Integer> faultyStep = StepStub.throwsException(new TimeoutException("Gateway timeout"));

Flow<Integer, Integer> flow = Flows.<Integer>begin("test")
        .step("step-1", doubler)
        .build();

flow.call(10);

// 断言桩的调用情况
Assert.assertEquals(1, doubler.invocationCount());
Assert.assertEquals(Integer.valueOf(10), doubler.lastInput());
```

### 2.2 副作用桩 (`ActionStub`)
```java
ActionStub<OrderContext> auditStub = ActionStub.create();

Flow<OrderContext, OrderContext> flow = Flows.<OrderContext>begin("test")
        .tap("audit", auditStub)
        .build();

flow.call(order);

Assert.assertEquals(1, auditStub.invocationCount());
Assert.assertSame(order, auditStub.lastInput());
```

### 2.3 守卫条件桩 (`ConditionStub`)
```java
ConditionStub<Integer> conditionStub = ConditionStub.always(true);
```

---

# 3. 流畅断言工具 (`FlowAssertions`)

### 3.1 结果断言 (`FlowResultAssert`)

```java
FlowExecution<String> execution = flow.run("input");

// 1. 成功断言
FlowAssertions.assertThat(execution.result())
        .isSucceeded()
        .hasValue("expected-value");

// 2. 业务停止断言
FlowAssertions.assertThat(execution.result())
        .isStopped()
        .hasStopCode("INVALID_PARAM");

// 3. 技术失败断言
FlowAssertions.assertThat(execution.result())
        .isFailed()
        .hasFailedNodeId("call-remote")
        .hasCauseInstanceOf(TimeoutException.class)
        .hasCauseMessage("Gateway timeout");
```

### 3.2 轨迹断言 (`FlowTraceAssert`)

用于精确验证复杂流程的执行节点顺序、分支命中与节点状态：

```java
FlowAssertions.assertThat(execution.trace())
        .hasExecutedNode("validate")
        .hasExecutedNode("reserve")
        .hasExecutionOrder("validate", "reserve", "choose-pay", "build-receipt")
        .hasNodeStatus("validate", FlowResult.Kind.SUCCEEDED)
        .hasBranchSelected("choose-pay", "CARD")
        .hasNodeCount(4);
```
