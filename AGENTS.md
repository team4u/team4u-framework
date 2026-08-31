# Team4u Framework - Agent Guidelines & Development Runbook

本文件面向在 `team4u-framework` 代码库中协同开发的 AI 智能体与工程师，记录工程构建、测试最佳实践、架构约束与代码规范。

---

## 常用命令与并行构建规范

### 全工程并行构建与测试（强烈推荐）

由于本工程包含 **59 个 Maven 模块**，使用单线程构建耗时较长（约 85 秒）。**必须使用 Maven 提供的 `-T` 多线程并行构建参数**，利用多核 CPU 按模块依赖拓扑并行编译与运行测试：

```bash
# 全工程并行运行全量单测（耗时仅需 ~19 秒，提速 75%+）
mvn test -T 1C

# 全工程清理并并行运行单测
mvn clean test -T 1C

# 指定线程数并行构建（例如 4 线程）
mvn test -T 4
```

> [!TIP]
> `-T 1C` 表示每个 CPU 核心分配 1 个并发构建线程（1 Thread per Core），可在保证构建稳定性的同时最大化利用本地硬件资源。

### 单模块测试

针对正在开发的单一子模块，使用 `-pl`（project list）参数定向运行单测，避免全量构建：

```bash
# 运行 flow 核心模块单测
mvn test -pl modules/flow/core

# 运行 flow 图表可视化模块单测
mvn test -pl modules/flow/diagram

# 运行多个指定模块单测
mvn test -pl modules/config/db,modules/config/core,modules/flow/diagram
```

### 架构依赖边界校验 (Maven Enforcer)

工程严格通过 `maven-enforcer-plugin` 约束各核心模块的零依赖边界。验证依赖合规性：

```bash
mvn enforcer:enforce
```

---

## 单测性能与编写规范

1. **避免真实时间 Sleep 与长时间轮询**：
   - 涉及周期性轮询（如 `ConfigWatcher`、心跳检测、刷新缓存等）的测试，必须支持或配置**毫秒级**时间间隔（如 `Duration.ofMillis(20~50)`），严禁在单测中使用大于 100ms 的硬编码等待；
   - 优先使用条件等待（如 `awaitTrue(...)` 或虚拟时钟 `MutableClock`）代替固定的 `Thread.sleep`。

2. **控制极端防爆栈测试的规模**：
   - 验证非递归栈安全的测试，嵌套层级建议设定为 **1200 ~ 1500 层**（足以超越 JVM 默认 1MB 栈的 1000~1024 栈溢出阈值），避免不必要的 $O(N^2)$ 集合拷贝与超大字符串开销。

---

## 模块架构与包路径规范

1. **零依赖与轻量化原则**：
   - 各核心模块（`core`）保持纯 Java 8 实现，绝不引入 Spring、Jackson、ByteBuddy 等第三方重量级依赖；
   - 扩展适配统一放入独立的子模块（如 `*-spring`、`*-jackson`、`*-proxy`）。

2. **模块重命名与兼容性**：
   - `team4u-flow-diagram` 统一承载 Flow 的 Mermaid 流程图与文本树渲染；
   - 核心入口类为 `FlowDiagrams.mermaid()` 与 `FlowDiagrams.text()`。
