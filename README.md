# Team4u Framework 架构与组件文档索引

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

`team4u-framework` 是一个轻量级、模块化、高可扩展的 Java 基础架构组件套件。组件遵循“**轻量无强制依赖、配置即规则、策略易扩展、接口统一、性能极致**”的设计理念，帮助开发者降低业务复杂度、规范架构模式并提高系统稳定性与研发效能。

---

## 组件分类导航

### 业务路由与规则控制

将多维度的业务决策逻辑、灰度分流、人群圈选与契约转换结构化、外部化管理。

| 组件 | 对应模块 | 说明与核心场景 | 文档入口 |
| :--- | :--- | :--- | :--- |
| **[路由组件](docs/router/README.md)** | `team4u-router` / `team4u-router-proxy` | 插件化业务路由框架。router 支持 Map、Expression、Weight 与 Composite 路由、Trace 与拦截器；`team4u-router-proxy` 提供 `@Routed` 声明式接口代理与 Bean 定位。 | [概览](docs/router/README.md) · [快速开始](docs/router/quick-start.md) · [声明式路由](docs/router/router-declarative.md) |
| **[Criterion 表达式组件](docs/criterion/README.md)** | `team4u-criterion` | 低开销业务规则 DSL 表达式引擎。支持类 SQL 自然语法、JIT 闭包直出、低分配数值宽容比较、白盒 Trace 执行树与外部属性延迟加载 (`LazyAttributeResolver`)。 | [概览](docs/criterion/README.md) · [快速开始](docs/criterion/quick-start.md) · [基准](benchmarks/README.md) |
| **[契约翻译组件](docs/translator/README.md)** | `team4u-translator` | 统一契约与响应翻译框架。将上游/底层原始响应 (`RawResponse`) 经由路由规则映射并渲染为统一对外契约 (`TranslatedResponse`)，内置模板变量插值与多级降级策略。 | [概览](docs/translator/README.md) · [快速开始](docs/translator/quick-start.md) |

---

### 配置中心与动态治理

统一配置抽象与规则管理，支持热更新、类型安全代理与动态策略发现。

| 组件 | 对应模块 | 说明与核心场景 | 文档入口 |
| :--- | :--- | :--- | :--- |
| **[配置组件](docs/config/README.md)** | `team4u-config-core` / `team4u-config-proxy` / `team4u-config-spring` / `team4u-config-db` | 强类型安全配置框架。支持快照驱动 (`Snapshot`)、Live/Pinned 代理双模、环境变量/属性文件/数据库多源聚合、占位符嵌套解析与防抖热更新。core 可独立使用，代理 / Spring / DB 适配按需显式引入。 | [概览](docs/config/README.md) · [快速开始](docs/config/quick-start.md) |
| **[策略模式组件](docs/policy/README.md)** | `team4u-policy` | 高性能策略管理与责任链引擎。提供 O(1) 复杂度 Copy-On-Write 读写分离精准路由 (`KeyedPolicy`)、有序责任链 (`OrderedPolicyChain`)、中断流水线与 Spring 自动发现 (`@PolicyAutoRegister`)。 | [概览](docs/policy/README.md) · [快速开始](docs/policy/quick-start.md) |

---

### 服务治理与分布式协同

提供分布式排他任务调度与统一容灾重试治理能力。

| 组件 | 对应模块 | 说明与核心场景 | 文档入口 |
| :--- | :--- | :--- | :--- |
| **[租约任务组件](docs/lease/README.md)** | `team4u-lease-core` / `team4u-lease-memory` / `team4u-lease-jdbc` | 排他任务调度框架。通过队列化 Task/Worker/Result API 提供延迟调度、精确类型订阅、心跳续约、故障接管与业务键幂等建档，Memory / JDBC 持久化后端按需显式引入。 | [概览](docs/lease/README.md) · [快速开始](docs/lease/quick-start.md) |
| **[通用重试组件](docs/retry/README.md)** | `team4u-retry-core` / `team4u-retry-managed` | 统一重试治理框架。core 提供进程内即时同步/异步重试 (`INLINE`)，managed 提供基于租约持久化的跨进程后台托管补偿重试 (`MANAGED`) 与 `@Retryable` 动态策略下发；按需适配 `team4u-retry-proxy`、`team4u-retry-config`、`team4u-retry-spring`、`team4u-retry-lease-runtime` 显式引入。 | [概览](docs/retry/README.md) · [快速开始](docs/retry/quick-start.md) |

---

### 数据安全与日志治理

聚焦敏感数据安全保护与低成本、高可观测的结构化日志体系。

| 组件 | 对应模块 | 说明与核心场景 | 文档入口 |
| :--- | :--- | :--- | :--- |
| **[数据脱敏组件](docs/mask/README.md)** | `team4u-mask` / `team4u-mask-jackson` / `team4u-mask-config` | 纯 Java 核心脱敏、Jackson 序列化适配与配置中心动态规则，按需显式引入。 | [概览](docs/mask/README.md) · [快速开始](docs/mask/quick-start.md) |
| **[结构化日志组件](docs/log/README.md)** | `team4u-log-core` / `team4u-log-governance` | 流式结构化日志核心默认输出未经脱敏的 RAW/UNMASKED 明文；治理 artifact 显式集成 Jackson、配置热更新、脱敏、方法代理、染色与 FinOps 限流。 | [概览](docs/log/README.md) · [快速开始](docs/log/quick-start.md) |

---

### 核心基础与架构支撑

打磨基础开发体验，提供零框架强绑定的设计模式与工具集。

| 组件 | 对应模块 | 说明与核心场景 | 文档入口 |
| :--- | :--- | :--- | :--- |
| **[对象容器组件](docs/bean/README.md)** | `team4u-bean` / `team4u-bean-spring` | 轻量级 Bean 容器与对象管理门面。核心为纯 Java 本地容器与 Provider 链式查找；Spring 桥接作为独立普通配置显式引入。 | [概览](docs/bean/README.md) · [快速开始](docs/bean/quick-start.md) |
| **[动态代理组件](docs/proxy/README.md)** | `team4u-proxy` | 统一代理门面与 AOP 拦截器链。自适应 JDK Proxy / ByteBuddy 双引擎，开箱提供方法委托 (鸭子类型)、调用链追踪 (`Tracker`)、运行时热替换 (`HotSwap`) 与空对象防 NPE 代理。 | [概览](docs/proxy/README.md) · [快速开始](docs/proxy/quick-start.md) |
| **[序列化组件](docs/serializer/README.md)** | `team4u-serializer-json` / `team4u-serializer-jackson` | 统一 JSON 序列化门面 (`JsonUtil`)。json 为 provider-free 核心与 SPI，基于自动扫描与优先级加载，支持复杂泛型 `TypeReference` 提取与容错解析；Jackson 驱动经 `team4u-serializer-jackson` 显式引入。 | [概览](docs/serializer/README.md) · [快速开始](docs/serializer/quick-start.md) |
| **[核心基础组件](docs/base/README.md)** | `team4u-base` / `team4u-base-jdbc` | 框架基石与通用工具库。提供分段锁动态实例创建 (`DynamicInstanceProvider`)、高性能预解析文本模板 (`TextTemplate`)、通用缓存 (`LRU/LFU/TimedCache`) 与类型转换器；JDBC 构建工具位于独立的 `team4u-base-jdbc`。 | [概览](docs/base/README.md) · [快速开始](docs/base/quick-start.md) |

---

## 快速接入

在项目的 `pom.xml` 中引入 `team4u-framework` 依赖管理（BOM）：

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

按需引入各功能模块：

```xml
<dependencies>
    <!-- 业务路由模块 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-router</artifactId>
    </dependency>

    <!-- 声明式路由代理模块（@Routed / RoutedProxyFactory） -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-router-proxy</artifactId>
    </dependency>

    <!-- 表达式引擎模块 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-criterion</artifactId>
    </dependency>

    <!-- 配置中心核心模块 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-config-core</artifactId>
    </dependency>

    <!-- 策略模式模块 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-policy</artifactId>
    </dependency>

    <!-- 统一重试模块 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-retry-core</artifactId>
    </dependency>

    <!-- 数据脱敏核心，Jackson / 配置中心适配按需显式引入 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-mask</artifactId>
    </dependency>
    <!-- Jackson 脱敏 / 配置中心动态规则按需显式引入 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-mask-jackson</artifactId>
    </dependency>
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-mask-config</artifactId>
    </dependency>

    <!-- 结构化日志核心（默认输出未经脱敏的 RAW/UNMASKED 明文） -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-log-core</artifactId>
    </dependency>
    <!-- Jackson / 配置 / 脱敏 / 代理 / Spring 治理按需显式引入 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-log-governance</artifactId>
    </dependency>
</dependencies>
```

---

## 设计哲学

- **轻量与解耦（Lightweight & Decoupled）**：核心模块不强制绑定 Spring 或重量级中间件，既可在纯 Java / CLI 环境高效运行，又能与 Spring 生态无缝整合。
- **配置即规则（Configuration as Rule）**：将变动频繁的业务规则（路由、策略、脱敏、重试、错误映射）外部化与配置化，支持运行时热更新与动态生效。
- **策略可插拔（Extensible by Policy）**：核心扩展点均基于策略模式与统一注册器设计，支持 SPI、Spring Bean 与运行时手动注册。
- **性能与低分配（High Performance & Low Allocation）**：关键路径采用无锁设计、Copy-On-Write 机制、JIT 闭包预编译与原生类型快速比较，降低高频调用的临时分配和 GC 压力；实测值与环境说明见 JMH 基准。
- **白盒可观测（White-Box Observability）**：关键决策链路（如表达式判定、动态路由、重试接管、方法耗时）内置 Trace 诊断树，让复杂逻辑透明直观。
