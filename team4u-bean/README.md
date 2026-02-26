# Bean 管理模块

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## 核心优势

- **轻量级与零依赖**：核心代码不强绑定 Spring，提供了一套独立的容器管理方案。这使得基础组件库可以独立运行，不再为“为了用一个 Bean 管理而引入整个 Spring 家族”而烦恼。
- **职责分离 (SRP)**：通过 `BeanFactory`（读）与 `BeanRegistry`（写）接口的隔离，确保了容器操作的语义清晰，符合接口隔离原则。
- **真正的并发安全**：底层基于 `ConcurrentHashMap`，并利用 `putIfAbsent` 等原子化操作实现无锁化读取与安全注册，彻底消除了高并发启动或懒加载时的线程竞争隐患。
- **拥抱 SPI 与自动化**：支持 Java 标准 SPI 机制，扩展容器只需实现接口即可自动发现。
- **无缝 Spring 桥接**：通过 `SpringBeanContainer` 适配器，可以在 Spring 环境下自动将外部 Bean 注入到统一的管理门面中，实现“两套体系，一套接口”。

## 为什么不直接使用 Spring？

Spring 是非常优秀的容器，但在开发**底层中间件**或**通用 SDK** 时，我们面临以下挑战：

- **侵入性过强**：强制要求用户环境必须有 Spring 上下文。
- **启动开销**：在某些轻量级工具或 CLI 应用中，启动 Spring 容器过重。
- **循环依赖困境**：在某些底层初始化阶段，Spring 可能尚未完全就绪。

`team4u-bean` 提供了“可插拔”的方案：在非 Spring 环境下，它是一个高性能的本地单例桶；在 Spring 环境下，它能自动变身为 Spring 的影子代理。

## 核心架构概览

该模块采用了 **Provider 链式查找模式**：

- **BeanFactory (读)**：定义了如何查找 Bean。
- **BeanRegistry (写)**：定义了如何注册 Bean。
- **BeanManager (门面)**：核心调度器，持有多个 `BeanFactory` 实例，并按优先级（Order）进行链式查找。
- **LocalBeanContainer**：默认的本地容器，作为所有 Bean 的兜底存储。
- **SpringBeanContainer**：Spring 环境适配器，优先级高于本地容器。

---

## 场景一：基础 Bean 管理

当你需要在一个非 Spring 框架的普通 Java 项目中管理全局单例时。

### 注册与获取

```java
import com.team4u.framework.bean.BeanManager;

BeanManager manager = BeanManager.getInstance();

// 注册 Bean
manager.registerBean("myService", new MyServiceImpl());

// 按名称获取
MyService service = manager.getBean("myService");

// 按类型获取
MyService serviceByType = manager.getBean(MyService.class);
```

---

## 场景二：智能懒加载 (Lazy Loading)

当你希望 Bean 仅在第一次被访问时才进行初始化（如创建数据库连接池、初始化昂贵的资源）。

```java
// 如果容器中不存在，则触发 lambda 表达式创建并注册
MyService service = BeanManager.getInstance().loadBean(MyService.class, () -> {
    // 复杂的初始化逻辑
    return new MyServiceImpl();
});
```
> **提示**：`loadBean` 内部使用了线程安全的原子操作，确保即使在极高并发下，初始化逻辑也只会被执行一次。

---

## 场景三：Spring 环境无缝桥接

当你的 SDK 运行在 Spring 环境中时，只需将 `SpringBeanContainer` 声明为 Spring Bean 即可。

### Spring 配置

```java
@Configuration
public class MyFrameworkConfig {

    @Bean
    public SpringBeanContainer springBeanContainer() {
        // 一旦该 Bean 被 Spring 初始化，它会自动将自己挂载到 BeanManager 中
        return new SpringBeanContainer();
    }
}
```

### 使用效果

```java
// 即使代码在底层 SDK 中，也能通过 BeanManager 直接拿到 Spring 容器里的 Bean
UserService userService = BeanManager.getInstance().getBean(UserService.class);
```

---

## 辅助功能：SPI 扩展

为了实现完全的解耦，你可以通过 `META-INF/services/` 自动注册自定义的 `BeanFactory`。

1. 实现 `BeanFactory` 接口并指定 `order()`。
2. 在 `src/main/resources/META-INF/services/com.team4u.framework.bean.core.BeanFactory` 中填入实现类全路径。
3. `BeanManager` 启动时会自动加载并按优先级排序。

---

## API 速查表

| 组件 | 角色 | 关键特性 |
| --- | --- | --- |
| `BeanManager` | 门面 (Facade) | 线程安全单例，支持 SPI 自动发现，统一查找入口。 |
| `LocalBeanContainer` | 本地存储 | 默认兜底，基于 ConcurrentHashMap，实现 O(1) 访问。 |
| `SpringBeanContainer` | 桥接器 | 实现了 ApplicationContextAware，自动代理 Spring 容器查询。 |
| `loadBean` | 核心方法 | 具备“双重检查锁”等效性能的无锁懒加载注册。 |

## 最佳实践

- **优先按类型获取**：`getBean(Class<T>)` 比 `getBean(String)` 更安全，且能获得更好的 IDE 支持。
- **利用 Order 机制**：如果你有多个容器源，可以通过 `order` 调整查找顺序（如：Redis 缓存容器 -> 本地容器）。
- **解耦设计**：在编写 SDK 代码时，建议通过 `BeanManager.getInstance().getBean(...)` 获取依赖，而不是使用静态工厂或强绑定 Spring 注解。
