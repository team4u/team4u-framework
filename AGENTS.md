# Team4u Framework - Agent Guidelines & Development Runbook

本文件面向在 `team4u-framework` 代码库中协同开发的 AI 智能体与工程师，记录工程构建、测试最佳实践、架构约束与代码/文档规范。

---

## 常用命令与并行构建规范

### 全工程并行构建与测试

本工程包含 **60 个 Maven 模块**。推荐使用 Maven 提供的 `-T` 多线程并行构建参数，按依赖拓扑并行编译与测试：

```bash
# 全工程并行运行全量单测（推荐 4 线程，耗时仅需 ~21 秒，兼顾速度与稳定性）
mvn test -T 4

# 若需清理后测试，必须先单独 clean 再并行 test（严禁组合在一条命令中带 -T clean）
mvn clean && mvn test -T 4
```

> [!WARNING]
> **严禁执行 `mvn clean test -T ...` 并发组合命令**：
> Maven 的 `clean` 阶段在多线程并行执行时，会并发删除其他线程正在编译或测试的 `target` 目录，引发“文件不存在”或类丢失等伪构建失败。

> [!TIP]
> **并发线程数选型建议（为什么推荐 `-T 4` 而非 `-T 1C`）**：
> 在多核机器（如 16 核）上，若使用 `-T 1C` 会瞬间拉起 16 个 Surefire forked JVM 进程与编译器实例，导致剧烈的磁盘 I/O 争抢与进程间 IPC 通信超时；实测 **`-T 4` 是兼顾多核利用率与系统稳定性的黄金平衡点**，全工程全量测试耗时仅需 21 秒。

### 单模块与关联模块测试

针对正在开发的单一子模块，使用 `-pl`（project list）参数定向运行单测，避免全量构建：

```bash
# 运行 flow 核心模块单测
mvn test -pl modules/flow/core

# 运行 flow 图表可视化模块单测
mvn test -pl modules/flow/diagram

# 若同时修改了底层 base/core 模块，需在 -pl 中同时带上被修改的上游模块
mvn test -pl modules/base/core,modules/flow/core,modules/flow/log
```

### 架构依赖边界校验 (Maven Enforcer)

工程严格通过 `maven-enforcer-plugin` 约束各核心模块的零依赖边界。验证依赖合规性：

```bash
mvn enforcer:enforce
```

---

## 文档排版与表达风格规范

### 严禁使用表情符号 (No Emoji)

在文档、代码、注释、Mermaid 流程图、日志输出及回复中，**严禁使用任何 Emoji 表情符号**，保持工程严谨、整洁的专业风格。

### 文档标题与章节去序号化 (No Numbered Headings)

编写或更新 Markdown 文档时，各级标题（`#`、`##`、`###`、`####`）**尽可能不使用数字序号或中文序号前缀**（例如避免 `1.`、`1.1`、`一、`、`（一）`、`1、` 等），使目录结构扁平清晰，避免生成目录或侧边栏时出现重复编号冲突。

---

## 单测性能与编写规范

### 避免真实时间 Sleep 与长时间轮询

- 涉及周期性轮询（如 `ConfigWatcher`、心跳检测、刷新缓存等）的测试，必须支持或配置**毫秒级**时间间隔（如 `Duration.ofMillis(20~50)`），严禁在单测中使用大于 100ms 的硬编码等待；
- 优先使用条件等待（如 `awaitTrue(...)` 或虚拟时钟 `MutableClock`）代替固定的 `Thread.sleep`。

### 控制极端防爆栈测试的规模

- 验证非递归栈安全的测试，嵌套层级建议设定为 **1200 ~ 1500 层**（足以超越 JVM 默认 1MB 栈的 1000~1024 栈溢出阈值），避免不必要的 $O(N^2)$ 集合拷贝与超大字符串开销。
