# Team4u Base 核心基础模块

`team4u-base` 是整个 Team4u Framework 的基石模块。它不仅定义了框架底层的核心抽象，还提供了一系列高性能、健壮的工具类和实例管理机制，旨在为上层业务模块提供统一且可靠的支撑。

## 目录
- [核心特性](#核心特性)
- [关键组件详解](#关键组件详解)
  - [动态实例提供者 (DynamicInstanceProvider)](#动态实例提供者-dynamicinstanceprovider)
  - [单例工厂 (SingletonFactory)](#单例工厂-singletonfactory)
  - [健壮的服务加载器 (ServiceLoaderUtil)](#健壮的服务加载器-serviceloaderutil)
- [配置解析体系](#配置解析体系)
- [依赖说明](#依赖说明)

---

## 核心特性

* **高性能实例缓存**：内置分段锁 (Striped Locking) 机制，在高并发创建实例时能有效减少锁竞争，性能优于传统的全局锁方案。
* **双重检查锁 (DCL) 优化**：所有工厂类均严格遵循单例与 DCL 模式，确保复杂对象的安全初始化。
* **健壮的 SPI 加载**：增强了 Java 原生 `ServiceLoader` 的异常捕获与容错能力，避免单个损坏的实现类导致整个服务加载失败。
* **高度抽象化**：提供了从“输入源 -> 配置解析 -> 实例创建”的完整流水线抽象，支持任意维度的对象复用。

---

## 关键组件详解

### 动态实例提供者 (DynamicInstanceProvider)

这是框架中最高频使用的组件之一，常用于将“配置字符串”或“配置对象”转换为“可执行实例”（如拦截器、策略对象）。

* **分段锁设计**：内部维护 128 个锁桶，根据 Key 的哈希值动态路由，极大提升并发吞吐量。
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

* **Hutool (Core/Cache)**：利用其成熟的工具函数和缓存算法（LRU/LFU）。
* **Lombok**：简化冗长的 POJO 代码。
* **JUnit**：完备的单元测试支撑。

---

[返回项目主页](../README.md)
