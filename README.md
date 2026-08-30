# Team4u Framework

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

> 简单、克制、趁手。面向 Java 研发者的轻量级基础架构套件。

team4u-framework 是一组专注解决后端常见架构模式的基础组件库。它像一把折叠整齐的瑞士军刀：没有庞大笨重的依赖包，每一个模块都经过细致打磨，小巧、独立、各司其职，在需要时随时取用。

[进入官方完整文档中心 (docs/README.md)](docs/README.md)

---

## 设计思考

- **克制与无侵入**：核心模块基于纯 Java 编写，不强制依赖 Spring、ByteBuddy 或特定的 JSON 解析器。在纯 CLI 工具、轻量应用或微服务体系中均可平滑运行。
- **正交的二维结构**：工程统一采用 `modules/<业务族>/<运行时变体>` 目录组织。核心库只保留纯粹的算法与状态机，Spring 或特定中间件适配作为独立依赖显式引入，不给工程引入多余的传递依赖。
- **配置即规则**：将变动频繁的业务分支（多维路由、限流阈值、脱敏规则、重试策略）抽离为纯粹的配置模型，支持运行时防抖热重载，减少重复编码。
- **关注微开销与确定性**：关键执行路径采用无锁读写分离、预编译闭包与原生数值运算，降低高频调用下的临时对象分配与 GC 负担，追求确定、透明的运行表现（[查看 JMH 压测实测数据](benchmarks/README.md)）。
- **白盒可观测**：重要的决策过程（表达式判定、路由分流、故障接管）内置树状 Trace 结构，便于开发调试与排查。

---

## 常用工具概览

| 组件 | 对应模块 | 解决的典型问题 | 文档 |
| :--- | :--- | :--- | :--- |
| **业务路由** | `team4u-router` | 多维条件分流、权重分流与复合决策，替代深层嵌套的 `if-else`。 | [文档](docs/router/README.md) |
| **规则表达式** | `team4u-criterion` | 纯 Java 实现的类 SQL 语法 DSL 引擎，低分配且支持执行链路白盒追踪。 | [文档](docs/criterion/README.md) |
| **回源合并** | `team4u-singleflight` | 抑制高并发下相同 Key 的瞬时击穿与惊群效应，支持结果共享与超时接管。 | [文档](docs/singleflight/README.md) |
| **多算法限流** | `team4u-ratelimiter` | 提供固定窗口、滑动窗口、令牌桶等算法，基于 KV 能力自动协商。 | [文档](docs/ratelimiter/README.md) |
| **排他任务租约** | `team4u-lease` | 基于租约心跳的任务抢占与故障接管机制，保障单点排他执行。 | [文档](docs/lease/README.md) |
| **容灾重试** | `team4u-retry` | 统一进程内即时重试与后台托管调度，解耦重试策略与业务执行。 | [文档](docs/retry/README.md) |
| **动态脱敏** | `team4u-mask` | 纯 Java 字段掩码与 Jackson 自动脱敏，支持规则热更新。 | [文档](docs/mask/README.md) |
| **配置治理** | `team4u-config` | 强类型快照读取与接口代理，支持多数据源聚合与防抖更新。 | [文档](docs/config/README.md) |
| **基础支撑** | `team4u-proxy` / `team4u-kv` / `team4u-base` | 动态代理、多级 KV 缓存抽象与通用基础工具集。 | [文档](docs/base/README.md) |

各组件更详细的背景、架构设计与高级用法，请参阅 [官方完整文档中心 (docs/README.md)](docs/README.md)。

---

## 快速接入

在工程的 `pom.xml` 中导入统一依赖管理（BOM）：

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

按需引入具体的独立模块（主入口使用裸 ArtifactId，无需声明版本号）：

```xml
<dependencies>
    <!-- 引入业务路由 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-router</artifactId>
    </dependency>

    <!-- 引入轻量限流 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-ratelimiter</artifactId>
    </dependency>

    <!-- 引入回源防击穿 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-singleflight</artifactId>
    </dependency>
</dependencies>
```

---

## 导航与参考

- [官方文档中心 (docs/README.md)](docs/README.md)：包含各组件的设计细节、API 手册与实战示例。
- [1.0 迁移指南 (MIGRATION-1.0.md)](MIGRATION-1.0.md)：版本升级说明与模块拆分对照。
- [1.0 不兼容变更清单 (breaking-changes-1.0.md)](docs/breaking-changes-1.0.md)：Breaking Changes 与应对方案。
- [JMH 基准测试记录 (benchmarks/README.md)](benchmarks/README.md)：核心热路径的单次耗时与内存分配实测数据。

---

## 开源协议

本项目采用 [MIT License](LICENSE) 协议。
