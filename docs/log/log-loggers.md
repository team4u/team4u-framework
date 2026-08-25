# 结构化流式日志 (Loggers)

`Loggers` 是 `team4u-log` 提供的流式日志构建器。它采用延迟执行模型，在调用 `.log()` 前仅进行对象装配，最后统一交付底层日志引擎处理。

---

## 核心 API 总览

| 方法 | 作用 | 默认行为 |
| :--- | :--- | :--- |
| `Loggers.of(Class<?> clazz)` | 静态工厂，为指定类创建构建器 | `loggerName = clazz.getName()` |
| `action(String action)` | 设置业务动作标识 | 默认 `null` |
| `status(String status)` | 设置业务状态（如 `"start"`, `"success"`, `"failed"`） | 默认 `null` |
| `success()` | 标记状态为 `"success"` | 若未指定 `level`，自动设置为 `INFO` |
| `failed(Throwable e)` | 标记状态为 `"failed"` 并绑定异常 | 若未指定 `level`，自动设置为 `ERROR` |
| `level(Level level)` | 显式指定日志级别 | `org.slf4j.event.Level` |
| `atTrace() / atDebug() / atInfo() / atWarn() / atError()` | 快捷设置对应日志级别 | 链式便捷方法 |
| `duration(long ms)` | 设置执行耗时（毫秒） | 默认 `-1` |
| `put(String key, Object value)` | 向 `payload` 添加单个 KV 键值对 | 写入底层 `LinkedHashMap` |
| `putAll(Map<String, Object> map)` | 批量添加 KV 键值对 | 批量合并至 `payload` |
| `derive()` | 派生当前构建器副本（浅拷贝 `payload`） | 用于创建日志模板 |
| `begin()` | 开启一个耗时区间追踪器 | 返回 `LogSpan` 实例 |
| `around(Runnable / Callable<T>)` | 闭包执行一段代码，自动记录耗时与异常 | 自动抛出业务异常 |
| `log()` | 提交日志事件到处理引擎 | 若当前级别未启用且无动态染色提权需要，快速短路跳过 |

---

## 基础用法

### 成功日志
```java
Loggers.of(OrderService.class)
       .action("CreateOrder")
       .put("orderId", "ORD-10086")
       .put("amount", 299.00)
       .success()                      // 自动设置 status="success", level=INFO
       .log();
```

### 异常日志
```java
try {
    paymentService.pay(orderId, amount);
} catch (Exception ex) {
    Loggers.of(PaymentService.class)
           .action("PayOrder")
           .put("orderId", orderId)
           .failed(ex)                 // 自动设置 status="failed", level=ERROR, exception=ex
           .log();
}
```

---

## 模板派生 (`derive()`)

在处理复杂的多步骤业务流程时，通常存在公共的模块标识或会话信息。可利用 `derive()` 将 `Loggers` 作为模板复用：

```java
public class OrderProcessor {

    // 1. 定义基础静态日志模板
    private static final Loggers BASE_LOG = Loggers.of(OrderProcessor.class)
            .put("module", "OrderEngine")
            .put("cluster", "SH-01");

    public void processOrder(String orderId, String userId) {
        // 2. 派生局部上下文，继承 module 与 cluster
        Loggers stepLog = BASE_LOG.derive()
                .put("orderId", orderId)
                .put("userId", userId);

        // 步骤 1：校验
        stepLog.derive()
                .action("ValidateOrder")
                .success()
                .log();

        // 步骤 2：库存扣减
        stepLog.derive()
                .action("DeductStock")
                .put("skuCount", 3)
                .success()
                .log();
    }
}
```

> [!NOTE]
> `derive()` 会对 `payload` 的 `LinkedHashMap` 执行浅拷贝。修改派生实例的顶层 KV 不会影响模板实例；但如果放入了可变对象的引用，内部对象依然共享。

---

## 区间耗时追踪 (`LogSpan` 与 `around`)

### `LogSpan`（显式分阶段记录）
通过 `begin()` 可以开启一个 `LogSpan`。支持在方法开始时打印一条 `start` 日志，并在方法结束时自动计算耗时：

```java
LogSpan span = Loggers.of(ExportService.class)
        .action("ExportReport")
        .put("reportId", "RPT-8899")
        .begin()
        .logStart(); // 立即以 INFO 级别输出 status="start" 的日志

try {
    doExportHeavyTask();
    
    // 自动计算 durationMs 并输出最终结果日志
    span.put("rowCount", 50000)
        .success()
        .log();
} catch (Exception e) {
    span.failed(e).log();
}
```

### `around()` 闭包包装
对于简单的方法调用，可以使用 `around` 一键包裹：

```java
// 无返回值
Loggers.of(OrderService.class)
       .action("CleanExpiredOrders")
       .around(() -> orderService.cleanExpired());

// 有返回值
Order order = Loggers.of(OrderService.class)
       .action("QueryOrder")
       .put("orderId", orderId)
       .around(() -> orderService.getOrderById(orderId));
```

> [!TIP]
> `around` 内部在发生异常时会自动捕获并记录 `failed(e).log()`，随后原样重新抛出原始异常（或用 `RuntimeException` 包装受检异常），不改变原有业务异常控制流。
