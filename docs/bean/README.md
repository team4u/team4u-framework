# 对象容器组件 (team4u-bean)

# 背景

在框架设计与多模块解耦中，框架核心代码不应直接硬编码依赖特定的 IoC 容器（如 Spring、Guice 等），但又必须能够在运行时无缝获取用户注册的业务 Bean、Service 单例与切面代理。

`team4u-bean` 提供了统一的抽象容器门面 `BeanManager`，支持按优先级自动桥接 Spring 容器与本地单例容器，提供基于 Java SPI 的动态扩展与组件生命周期事件监听。

---

# 核心特性

- **统一容器门面**：`BeanManager` 统一了按类型（Type）、按名称（Name）获取 Bean 的 API；
- **多级容器自动路由**：按优先级依次遍历 `SpringBeanContainer`、自定义 SPI 容器以及 `LocalBeanContainer`；
- **零反射直接调用**：解析完成后直接持有单例引用，运行期直接调用无性能损耗；
- **生命周期事件总线**：`EventDispatcher` 支持广播 `BeanInitializedEvent` 等容器事件；
- **Spring Boot 无缝集成**：通过 `@Import(Team4uBeanConfiguration.class)` 一键桥接 Spring 上下文。

---

## 模块坐标

```xml
<dependencies>
    <!-- 核心门面与本地容器 -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-bean</artifactId>
    </dependency>

    <!-- Spring 容器适配器 (按需引入) -->
    <dependency>
        <groupId>com.team4u</groupId>
        <artifactId>team4u-bean-spring</artifactId>
    </dependency>
</dependencies>
```

---

## 章节导航与专题专栏

- [快速开始](quick-start.md)：5 分钟掌握 Bean 注册与多环境检索。
- [容器门面与提供者体系](bean-container.md)：`BeanManager` 多级提供者链与查找优先级。
- [Spring 容器集成与配置](bean-spring.md)：`Team4uBeanConfiguration` 桥接与 AOP 代理保留。
- [自定义 BeanFactory SPI 扩展](bean-spi.md)：实现标准 SPI 接入第三方 IoC 容器。
- [容器事件派发与生命周期](bean-events.md)：`EventDispatcher` 与 `BeanInitializedEvent` 监听。
- [容器异常与诊断排查手册](bean-diagnostics.md)：`NoSuchBeanDefinitionException` 排查自查清单。
- [对象容器实战案例](bean-sample.md)：动态策略查找与插件化扩展实战。
