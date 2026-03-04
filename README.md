# Team4u Framework

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## 简介

Team4u Framework 是一个专为构建高性能、高可扩展性 Java 应用而设计的轻量级、模块化基础组件库。它旨在通过高度解耦的架构设计，屏蔽底层复杂性，为开发者提供一套统一、简洁且强大的编程模型。

无论是在构建微服务中间件、复杂业务系统，还是轻量级 SDK 时，Team4u 都能提供从基础容器到高级路由分流的核心支撑，帮助团队更专注于业务逻辑的实现。

---

## 核心哲学

- **极致性能**：关键路径采用无锁设计、Copy-On-Write 机制及 JIT 风格的预编译技术，确保在高并发场景下的稳定表现。
- **职责解耦**：倡导“策略驱动”与“声明式配置”，将业务决策逻辑与执行流程彻底分离。
- **零侵入与灵活性**：核心模块不强绑定 Spring，支持独立运行，同时提供完善的 Spring 桥接适配器，实现真正的“即插即用”。
- **可观测性**：内置完善的 Trace 诊断能力，让表达式计算、路由匹配等黑盒逻辑变得透明直观。

---

## 模块概览

| 模块 | 核心功能 | 典型场景 |
| :--- | :--- | :--- |
| [**team4u-bean**](./team4u-bean/README.md) | 轻量级 Bean 容器 | 独立 SDK 开发、单例管理、Spring 容器桥接 |
| [**team4u-proxy**](./team4u-proxy/README.md) | 动态代理与 AOP | 方法拦截、热交换 (HotSwap)、空对象安全防御 |
| [**team4u-retry**](./team4u-retry/README.md) | 统一重试治理模块 | 同步/异步重试、注解重试、动态策略、持久化降级 |
| [**team4u-criterion**](./team4u-criterion/README.md) | 逻辑表达式引擎 | 营销圈选、风控规则、动态配置过滤 |
| [**team4u-config**](./team4u-config/README.md) | 强类型配置管理 | 动态配置重载、多源配置聚合、热部署 |
| [**team4u-policy**](./team4u-policy/README.md) | 策略与责任链模式 | 支付渠道路由、风控拦截流、优惠计算 |
| [**team4u-log**](./team4u-log/README.md) | 结构化动态日志治理 | 自动化日志追踪、极速数据脱敏、热重载治理 |
| [**team4u-mask**](./team4u-mask/README.md) | 数据脱敏治理模块 | 字段脱敏、动态规则、Jackson 无侵入敏感信息保护 |
| [**team4u-router**](./team4u-router/README.md) | 声明式业务路由 | 业务分流、灰度控制、实验版本路由 |
| [**team4u-message**](./team4u-message/README.md) | 统一消息抽象框架 | 进程内事件总线、跨网络 MQ (Kafka/RocketMQ) 抽象 |
| [**team4u-base**](./team4u-base/README.md) | 基础公共工具类 | 框架内部公用辅助方法 |
---

## 核心组件详解

### 1. 表达式引擎 (Criterion)
不同于通用的数学计算引擎，`team4u-criterion` 专注于业务逻辑判定。支持类 SQL 的 DSL 语法（如 `age > 18 && tags contains 'VIP'`），并能通过可视化 Trace 还原每一层逻辑的匹配细节。

### 2. 路由管理 (Router)
基于配置驱动的逻辑分流器。支持精准匹配 (Map) 与复杂的表达式匹配 (Expression)，允许在不重启应用的情况下，通过修改配置中心规则实时调整业务流向。

### 3. 策略引擎 (Policy)
提供了 O(1) 复杂度的精准路由键匹配和基于优先级的有序责任链。通过 `PolicyScanner` 实现“实现即注册”，极大降低了策略模式的维护成本。

### 4. 动态代理 (Proxy)
融合了 JDK Proxy 与 ByteBuddy 引擎，提供职责链式的拦截器模型。支持在运行时动态替换代理背后的真实对象（HotSwap），为构建高弹性系统提供基础。

### 5. 消息抽象 (Message)
采用“信封模式”统一了本地事件与远程消息的处理逻辑。支持处理器级别的独立线程池隔离和全生命周期的拦截器注入。

---

## 快速开始

### 引入父工程

在您的项目 `pom.xml` 中引入 Team4u 依赖管理：

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
```

### 选择性引入模块

根据需求引入特定功能的模块，例如引入表达式引擎：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-criterion</artifactId>
</dependency>
```

---

## 环境要求

- **JDK**: 8 及以上版本。
- **依赖库**: 核心依赖 ByteBuddy、Hutool 等优秀开源库进行底层增强。

---

## 开源协议

本项目遵循 [MIT License](./LICENSE) 开源协议。
