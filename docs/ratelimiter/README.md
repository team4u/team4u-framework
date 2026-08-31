# 限流组件 (team4u-ratelimiter)

# 背景

在高并发服务端架构与分布式系统中，瞬时的突发流量、恶意爬虫防刷、依赖方不可控调用与第三方 API 配额限制常常对系统稳定性构成严重威胁。传统的限流方案通常面临以下困境：

- **算法单一且不贴合业务场景**：简单的固定计数器存在临界突刺风险，无法平滑整形流量；
- **硬编码侵入核心业务**：限流逻辑与业务代码深度耦合，无法声明式配置与无侵入接入；
- **降级不友好**：限流直接抛错导致接口 500，无法提供业务级友好的兜底响应；
- **缺乏多维层级限流**：难以支持从租户、用户、IP 到多级 API 路径的分层风控。

`team4u-ratelimiter` 是一个功能完备、低延迟、强类型的流量控制与限流治理组件。支持令牌桶、滑动窗口、固定窗口与历史路径算法，提供声明式 `@RateLimit` 注解、`@RateLimitReject` 优雅降级以及 Spring 自动装配。

---

# 核心特性

- **多算法支持**：内置工业级令牌桶（Token Bucket）、滑动时间窗口（Sliding Window）、固定窗口（Fixed Window）与历史层级路径窗口（History Window）；
- **声明式 `@RateLimit` 注解**：支持 SpEL 表达式动态提取参数 Key，支持指定时间周期与算法类型；
- **`@RateLimitReject` 优雅降级**：方法级 fallback 降级支持，拒绝时无缝重定向至业务兜底逻辑；
- **动态上下文与层级限流**：通过 `ContextProperties` 与 `HistoryPaths` 支持复杂多维路径联动限流；
- **Spring Boot 自动装配**：`@EnableRateLimit` 一键激活切面与单例引擎，零多余配置。

---

## 模块坐标

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-ratelimiter</artifactId>
</dependency>
```

---

## 章节导航与专题专栏

- [快速开始](quick-start.md)：5 分钟上手编程式限流与声明式注解。
- [限流算法深度解析](ratelimiter-algorithms.md)：令牌桶、滑动窗口、固定窗口与历史路径窗口算法全解。
- [动态上下文与分层路径限流](ratelimiter-context.md)：SpEL 动态属性提取与树状层级路径限流。
- [声明式注解与代理降级](ratelimiter-declarative.md)：`@RateLimit` 语法、动态代理拦截与 `@RateLimitReject` 降级。
- [Spring 集成与自动装配](ratelimiter-spring.md)：`@EnableRateLimit` 配置与 AOP 自动装配原理。
- [限流结果模型与异常诊断](ratelimiter-diagnostics.md)：`RateLimitResult` 结果模型、原因码与异常处理。
- [限流实战案例与最佳实践](ratelimiter-sample.md)：秒杀大促防刷与开放平台多租户配额控制实战。
