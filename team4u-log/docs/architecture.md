# team4u-log Architecture

这份文档解释 `team4u-log` 的实现机制，适合做二次扩展、理解性能特点，或在文档和代码之间建立精确映射。若你只是业务接入，先看 [../README.md](../README.md) 即可。

## 整体执行链路

当调用 `log()` 时，日志事件大致经历三段处理：

1. 事件构建
2. 拦截器流水线
3. 序列化与最终输出

统一模型是 `LogEvent`，无论来源是：

- 手工 `Loggers`
- `LogSpan`
- `@AutoLogTrace`
- `createProxy()`
- `createDynamicProxy()`

最终都会归一到这个模型。

## 事件构建层

### `Loggers`

`Loggers` 是最基础的结构化日志 Fluent API，负责积累：

- `action`
- `status`
- `durationMs`
- `payload`
- `exception`

只有调用 `log()` 时，才会把 `LogEvent` 提交给核心引擎。

### `derive()` 语义

`derive()` 会复制当前日志器状态，并调用 `LogEvent#derive()`：

- 顶层字段复制
- `payload` 顶层 Map 新建
- 内部可变对象不深拷贝

这也是它适合做模板、但不适合直接复用可变对象的原因。

### `LogSpan`

`begin()` 会返回 `LogSpan`，它在 `success()`、`failed()`、`status()` 时自动补 `durationMs`。`logStart()` 会用派生日志单独输出一条 `status = "start"` 的起始日志，不影响最终结果状态。

## 拦截器流水线

`LogEvent` 进入引擎后，会经过 `LogInterceptorManager` 调度的拦截器链。默认内置三类核心拦截器：

1. `MdcEnrichInterceptor`
2. `TargetedDyeingInterceptor`
3. `RateLimitInterceptor`

### `MdcEnrichInterceptor`

职责：

- 从 MDC 提取 `traceId`
- 支持自定义提取 key

如果你的链路字段叫 `requestId`，可以这样改：

```java
MdcEnrichInterceptor.getInstance().setTraceIdKey("requestId");
```

### `TargetedDyeingInterceptor`

职责：

- 读取 `team4u.log.dyeing`
- 在运行时根据上下文条件调整最终日志级别

它依赖 `team4u-criterion` 表达式引擎做条件判定。

### `RateLimitInterceptor`

职责：

- 对异常风暴做抑制
- 避免日志系统反过来放大底层故障

相关阈值来自 `team4u.log.finops`。

## Pull / Push 模型说明

染色规则匹配上下文已经重构为 Pull 模型。它和早期常见的 Push 思路差异在于：

- Push：先把所有上下文都展开，再交给规则引擎
- Pull：规则真正访问到哪个 key，系统才去按需取值

Pull 模型的收益：

- 少做无用上下文组装
- 规则维度扩展成本更低
- 对热路径更友好

规则里可见的上下文主要有三类：

- `payload` 业务字段
- MDC 上下文
- `meta_*` 元数据

如果内置维度不够，可以注册自定义 `LogContextSource`：

```java
LogContext.addSource((event, key) -> {
    if ("custom_key".equals(key)) {
        return "custom_value";
    }
    return null;
});
```

这段逻辑只有在表达式真正访问 `custom_key` 时才会执行。

## 自动代理链路

### `createProxy()`

`LogProxyFactory.createProxy()` 面向可控代码对象，底层通过 `team4u-proxy` 挂上 `LogTraceInterceptor`，再读取 `@AutoLogTrace` 注解配置来决定：

- action 名称
- 慢日志阈值
- 业务异常降级策略

### `createDynamicProxy()`

`createDynamicProxy()` 面向第三方对象或不方便加注解的实例，底层挂的是 `DynamicLogProxyInterceptor`。规则来自 `ProxyRuleRepository` 对配置 key `team4u.log.proxy` 的热加载结果。

你可以按类名配置：

- 允许拦截的方法
- `slowThreshold`
- `ignoreExceptions`

## 脱敏与序列化

日志模块本身不重复实现脱敏算法，而是在输出链路里接入 `team4u-mask`。

常见入口有两类：

- DTO 字段上的 `@Mask`
- 配置中心的 `team4u.mask.rules`

日志场景下的优先级：

1. `@Mask`
2. 类名精确匹配的动态规则
3. `*` 全局规则

序列化阶段还会做两类保护：

- 单字段长度限制
- `byte[]` 输出防御

## FinOps 保护

`team4u.log.finops` 控制三个关键阈值：

- `maxLogLength`
- `maxStringLength`
- `errorLimitPerSecond`

对应三类保护：

- 整条日志过长截断
- 单字段过长截断
- 同类异常日志限流

这些保护默认开启，目标是防止：

- 超大报文撑爆内存
- 故障风暴拖垮日志采集链路
- 告警系统被重复错误淹没

## `LogBootstrap` 生命周期

`LogBootstrap` 统一管理模块生命周期，核心入口有：

- `start()`
- `start(options)`
- `reconfigure(options)`
- `stop()`

启动时会联动初始化：

- `MaskBootstrap`
- `TargetedDyeingInterceptor`
- `FinOpsConfigRepository`
- `ProxyRuleRepository`
- `LogEngine`

所以推荐在应用启动阶段统一执行一次，而不是在业务代码里分散初始化。

## 自定义扩展

### 自定义拦截器

你可以通过编程方式注册，也可以走 SPI：

```java
LogEngine.getInstance()
         .getInterceptorManager()
         .register(new MyCustomInterceptor());
```

### 自定义 Appender

默认输出会走 SLF4J。如果你希望把结构化对象直接发到 Kafka、Elasticsearch 或其他远端系统，可以替换 `LogAppender`：

```java
LogEngine.getInstance().setAppender(new LogAppender() {
    @Override
    public void append(LogEvent event) {
        String json = LogEngine.getInstance().toJson(event);
        // send to remote system
    }
});
```

### Logback / Log4j2 配合

由于 `team4u-log` 已经把内容格式化成 JSON 字符串，底层日志框架建议只做最轻输出，例如 `%msg%n`，不要重复做格式化或再次 JSON 化。

## 何时需要看这份文档

以下场景适合继续往下读源码和这份架构文档：

- 你要接自定义规则上下文
- 你要改日志输出介质
- 你要解释为什么某些日志会被提权、限流或截断
- 你要给团队补“这套日志为什么性能开销可控”的背景材料
