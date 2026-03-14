# Team4u Base 核心基础模块

`team4u-base` 是整个 Team4u Framework 的基石模块。它定义了框架底层的核心抽象，并提供缓存、转换、实例管理与常用工具能力，供上层模块复用。

## 目录

- [核心特性](#核心特性)
- [关键组件详解](#关键组件详解)
    - [文本模板解析器 (TextTemplate)](#文本模板解析器-texttemplate)
    - [动态实例提供者 (DynamicInstanceProvider)](#动态实例提供者-dynamicinstanceprovider)
    - [单例工厂 (SingletonFactory)](#单例工厂-singletonfactory)
    - [健壮的服务加载器 (ServiceLoaderUtil)](#健壮的服务加载器-serviceloaderutil)
- [配置解析体系](#配置解析体系)
- [依赖说明](#依赖说明)

---

## 核心特性

* **实例创建阶段的并发控制**：`DynamicInstanceProvider` 在 Cache Miss 时通过分段锁降低实例创建阶段的锁竞争。
* **轻量缓存实现**：内置 `LRUCache`（`synchronized`）、`LFUCache`（单把 `ReentrantLock`）与 `TimedCache`，适合基础场景复用。
* **健壮的 SPI 加载**：增强了 Java 原生 `ServiceLoader` 的异常捕获与容错能力，避免单个损坏的实现类导致整个服务加载失败。
* **统一实例流水线**：提供从“输入源 -> 配置解析 -> 实例创建”的抽象流程，并明确区分输入缓存与配置缓存。

---

## 关键组件详解

### 文本模板解析器 (TextTemplate)

高性能的通用文本模板引擎，支持 `${property}` 格式占位符。专为高性能路由、动态配置和消息模板场景设计。

* **极致性能**：采用“预解析 + 运行时拼接”模式。在构造模板时将字符串拆分为静态段（Literal）和变量段（Placeholder），渲染时仅需简单的
  `StringBuilder` 拼接，彻底避开正则表达式的运行开销。
* **灵活渲染**：支持通过 `Map` 或 `Function`（值提供者函数）进行渲染。
* **变量自发现**：支持提取模板中定义的所有变量名，并保持其出现的顺序。

#### 基本用法

```java
// 1. 预解析模板（建议在初始化或静态块中完成并缓存实例）
TextTemplate template = new TextTemplate("biz.${region}.${tenantId}.router");

// 2. 提取变量名
Set<String> vars = template.getVariableNames(); // ["region", "tenantId"]

// 3. 多样化渲染
// 方式 A：通过 Map 渲染
Map<String, String> context = new HashMap<>();
context.put("region", "shanghai");
context.put("tenantId", "alipay");
String result1 = template.render(context); // "biz.shanghai.alipay.router"

// 方式 B：通过 Function 渲染（极速桥接任意数据源，如 Bean、配置库等）
String result2 = template.render(prop -> {
    return MyConfigManager.get(prop); // 从自定义配置源中提取值
});
```

### 动态实例提供者 (DynamicInstanceProvider)

这是框架中最高频使用的组件之一，常用于将“配置字符串”或“配置对象”转换为“可执行实例”（如拦截器、策略对象）。

* **双缓存语义**：输入对象与配置对象使用独立缓存，避免不同语义的 key 相互污染。
* **分段锁设计**：内部维护独立锁桶，仅在实例创建阶段控制并发。
* **流程透明**：
    1. 查缓存（命中则直接返回）。
    2. 未命中时解析配置 (Input -> Config)。
    3. 通过工厂创建实例 (Config -> Instance)。
    4. 存入缓存。

### 单例工厂 (SingletonFactory)

基于 `DynamicInstanceProvider` 实现的通用单例桶。

```java
// 自动通过反射创建并缓存实例，确保全局唯一且线程安全
MyService service = SingletonFactory.getInstance(MyService.class);
```

### 健壮的服务加载器 (ServiceLoaderUtil)

在 Java 原生 SPI 基础上增加了错误容忍和详细的调试日志。即使某些 SPI 实现类因为类找不到或初始化异常，也不会中断其他合法实现的加载。

```java
// 安全加载所有可用的实现类
List<MyPlugin> plugins = ServiceLoaderUtil.loadAvailableList(MyPlugin.class);
```

---

## 配置解析体系

模块定义了统一的配置解析接口 `ConfigParser`：

* **ConfigParser<I, C>**：将输入 `I`（如 JSON、XML）转换为结构化配置 `C`。
* **StringConfigParser<C>**：专门处理字符串类型的输入，是许多动态配置场景的基石。

---

## 依赖说明

为了保持核心的轻量与高效，`team4u-base` 仅依赖于以下极简的技术栈：

* **自研缓存工具**：内置轻量级的 LRU、LFU 与定时过期缓存实现，避免核心模块引入额外缓存依赖。
* **Lombok**：简化冗长的 POJO 代码。
* **JUnit**：完备的单元测试支撑。

---

[返回项目主页](../README.md)
