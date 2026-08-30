# 快速开始

本章将通过实际示例展示如何使用 `team4u-flow` 编排业务流程。

---

## 1. 引入依赖

通过统一 BOM 引入核心流程组件：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.team4u</groupId>
            <artifactId>team4u-framework</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- 核心流程组件（零第三方运行时依赖） -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-flow</artifactId>
    </dependency>

    <!-- 可选：持久化执行器 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-flow-durable</artifactId>
    </dependency>

    <!-- 可选：测试支持 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-flow-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 2. 基础示例：类型化转换流水线

前后步骤的类型由编译器严格推导，`A -> B -> C` 类型不匹配将在编译期报错：

```java
Flow<Integer, String> flow = Flows.<Integer>begin("math-pipeline")
        .step("double", in -> in * 2)               // Integer -> Integer
        .step("add-one", in -> in + 1)              // Integer -> Integer
        .step("format", in -> "Result: " + in)      // Integer -> String
        .build();

// 同步调用
String result = flow.call(5); // "Result: 11"
```

---

## 3. 典型业务示例：电商下单履约

在复杂的电商下单场景中，通常包含：上下文维护、守卫校验、幂等外部扣减、支付分支选择与终态清理。

### 3.1 业务模型与上下文

```java
public class OrderContext implements Serializable {
    private final String orderId;
    private final long amount;
    private final String paymentChannel;
    private boolean stockReserved;
    private String receiptId;

    // 构造器、getter/setter 省略
}

public class Receipt implements Serializable {
    private final String orderId;
    private final String receiptId;
    private final long amount;

    // 构造器、getter/setter 省略
}
```

### 3.2 流程定义

```java
// 1. 定义子流程（卡支付 / 钱包支付）
Flow<OrderContext, OrderContext> cardPayFlow = Flows.<OrderContext>begin("card-pay")
        .tap("call-card-gateway", ctx -> ctx.setReceiptId("RCP-CARD-" + ctx.getOrderId()))
        .build();

Flow<OrderContext, OrderContext> walletPayFlow = Flows.<OrderContext>begin("wallet-pay")
        .tap("call-wallet-gateway", ctx -> ctx.setReceiptId("RCP-WALLET-" + ctx.getOrderId()))
        .build();

// 2. 编排主流程
Flow<OrderContext, Receipt> checkoutFlow = Flows.<OrderContext>begin("checkout")
        // 守卫校验：条件不满足时安全停止
        .guard("validate-order",
                order -> order.getAmount() > 0,
                order -> StopReason.of("INVALID_AMOUNT", "订单金额必须大于0"))
        
        // 副作用动作：利用 StepContext.invocationId() 进行外部幂等调用
        .tap("reserve-stock", (stepContext, order) -> {
            inventoryService.reserve(stepContext.invocationId(), order.getOrderId());
            order.setStockReserved(true);
        })
        
        // 条件分支路由
        .choose("choose-channel", OrderContext::getPaymentChannel)
            .when("CARD", cardPayFlow)
            .when("WALLET", walletPayFlow)
            .otherwiseStop(order -> StopReason.of("UNSUPPORTED_CHANNEL", order.getPaymentChannel()))
        .end()
        
        // 类型转换：转换为最终收据
        .step("build-receipt", order -> new Receipt(order.getOrderId(), order.getReceiptId(), order.getAmount()))
        
        // 技术失败兜底恢复
        .recover("fallback", (order, failure) -> {
            log.error("下单失败，进入降级: {}", failure.cause().getMessage());
            return FlowResult.succeeded(new Receipt(order.getOrderId(), "FALLBACK", order.getAmount()));
        })
        
        // 无论成功、停止或失败均执行的终态清理
        .ensure("cleanup-metrics", (order, completion) -> {
            metricsService.recordCheckout(completion.kind());
        })
        .build();
```

---

## 4. 执行模式

### 4.1 本地同步直接调用 (`call`)

适合常规同步接口，执行成功直接返回值，发生业务 STOPPED 或技术 FAILED 时抛出 `FlowRunException`：

```java
OrderContext order = new OrderContext("ORD-1001", 8800L, "CARD");
try {
    Receipt receipt = checkoutFlow.call(order);
    System.out.println("下单成功: " + receipt.getReceiptId());
} catch (FlowRunException e) {
    if (e.result().isStopped()) {
        System.out.println("业务终止: " + e.stopReason().message());
    } else {
        System.out.println("技术异常: " + e.getCause().getMessage());
    }
}
```

### 4.2 本地诊断执行 (`run`)

适合需要追踪各节点执行状态、耗时和执行树的场景：

```java
FlowExecution<Receipt> execution = checkoutFlow.run(order, RunOptions.builder()
        .executionId("exec-001")
        .trace(true)
        .build());

FlowResult<Receipt> result = execution.result();
if (result.isSucceeded()) {
    System.out.println("产物: " + result.value());
}

// 打印执行轨迹
execution.trace().entries().forEach(entry -> {
    System.out.println(entry.nodeId() + " -> " + entry.status() + " (" + entry.elapsedNanos() / 1_000_000 + "ms)");
});
```

### 4.3 持久化可恢复执行 (`DurableFlow`)

无需修改流程定义，直接注册到 `DurableRuntime` 即可获得崩溃恢复与 CAS 检查点能力：

```java
// 1. 初始化运行时
DurableStore store = new InMemoryDurableStore(); // 或接入数据库 / Redis 存储
DurableRuntime runtime = DurableRuntime.builder(store).build();

// 2. 注册为持久化流程（指定版本）
DurableFlow<OrderContext, Receipt> durableFlow = runtime.register(checkoutFlow, 1);

// 3. 启动执行（每一步自动落 CAS 快照）
DurableResult<Receipt> res = durableFlow.start("ORD-1001", order);

// 4. 若节点中途发生网络超时/宕机，重启后可无缝恢复：
DurableResult<Receipt> recovered = durableFlow.recover("ORD-1001");
```
