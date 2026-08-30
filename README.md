# Team4u Framework

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

> 🚀 **轻量级、模块化、高可扩展的 Java 基础架构组件套件**

`team4u-framework` 是一套遵循“**轻量无强制依赖、配置即规则、策略易扩展、接口统一、性能极致**”设计理念的 Java 基础组件套件。
通过规范化的二维架构设计（`modules/<family>/<variant>`），帮助开发者化繁为简，降低业务复杂度，规范微服务与单体系统的架构模式，显著提升研发效能与系统稳定性。

📖 **[👉 点击进入官方完整文档中心 (docs/README.md)](docs/README.md)**

---

## ✨ 核心特性

- 🪶 **轻量无强制依赖**：纯 Java 核心零框架绑定，既可在 CLI / 纯 Java 环境极速运行，又能与 Spring / Spring Boot 生态无缝整合。
- 📐 **清晰的二维模块化**：统一采用 `modules/<family>/<variant>` 结构，主入口使用裸 ArtifactId（如 `team4u-config`, `team4u-kv`, `team4u-retry` 等），按需引入，杜绝依赖污染。
- ⚙️ **配置即规则（Config-as-Rule）**：将变动频繁的业务路由、动态脱敏、分布式限流、容灾重试等规则外部化为 JSON 配置，支持运行时防抖热重载与动态生效。
- ⚡ **极致性能与低分配**：关键路径采用无锁设计、Copy-On-Write 机制与 JIT 闭包预编译，单核千万级吞吐与极低 GC 压力（[查看 JMH 基准实测报告](benchmarks/README.md)）。
- 🔍 **白盒诊断与可观测**：核心决策链路（动态路由、规则表达式、重试接管、方法耗时）内置 Trace 诊断树，让复杂逻辑透明直观。

---

## 📦 核心组件全览

| 能力领域 | 核心模块 | 说明与场景 | 官方文档 |
| :--- | :--- | :--- | :--- |
| **业务路由与规则** | `team4u-router` / `team4u-criterion` / `team4u-translator` | 插件化多维业务路由、DSL 表达式引擎与统一契约响应翻译 | [查看文档](docs/router/README.md) |
| **配置中心与策略** | `team4u-config` / `team4u-policy` | 类型安全配置框架（快照/代理双模、防抖热重载）与 O(1) 策略注册引擎 | [查看文档](docs/config/README.md) |
| **服务治理与协同** | `team4u-lease` / `team4u-retry` / `team4u-kv` / `team4u-ratelimiter` / `team4u-singleflight` / `team4u-id` | 分布式租约调度、统一容灾重试治理、键值存储、多算法限流、回源合并与序号生成 | [查看文档](docs/retry/README.md) |
| **数据安全与日志** | `team4u-mask` / `team4u-log` | 敏感数据动态脱敏（纯 Java/Jackson）与低开销结构化日志治理 | [查看文档](docs/log/README.md) |
| **架构支撑底座** | `team4u-proxy` / `team4u-bean` / `team4u-serializer` / `team4u-base` | 自适应动态代理（JDK/ByteBuddy）、轻量容器门面、统一 JSON 序列化与基础工具库 | [查看文档](docs/base/README.md) |

> 💡 **详细的组件分类索引、特性深度解析与完整包结构，请直接查阅 [👉 官方完整文档中心 (docs/README.md)](docs/README.md)。**

---

## 🚀 快速接入

### 1. 引入 Dependency Management (BOM)

在项目的 `pom.xml` 中引入 `team4u-framework` 统一版本管理：

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

### 2. 按需添加所需模块（无需声明版本号）

```xml
<dependencies>
    <!-- 业务路由与声明式代理 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-router</artifactId>
    </dependency>
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-router-proxy</artifactId>
    </dependency>

    <!-- 类型安全配置中心核心 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-config</artifactId>
    </dependency>

    <!-- 统一重试治理模块 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-retry</artifactId>
    </dependency>

    <!-- 结构化日志核心与治理 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-log</artifactId>
    </dependency>
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-log-governance</artifactId>
    </dependency>
</dependencies>
```

---

## 📚 文档与资源导航

- 📖 **[官方文档中心 (Docsify 首页)](docs/README.md)**：各组件概览、设计原理、配置说明与最佳实践。
- 🔄 **[1.0 迁移与升级指南 (MIGRATION-1.0.md)](MIGRATION-1.0.md)**：模块拆分、坐标变更与升级兼容说明。
- ⚠️ **[1.0 不兼容变更清单 (breaking-changes-1.0.md)](docs/breaking-changes-1.0.md)**：Breaking Changes 与应对方案。
- 📊 **[JMH 性能基准测试报告 (benchmarks/README.md)](benchmarks/README.md)**：热路径耗时与内存分配实测数据。

---

## 📄 开源协议

本项目采用 [MIT License](LICENSE) 开源协议。
