# 动态代理核心模块

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## 目录
- [简介](#简介)
- [快速入门](#快速入门)
- [核心特性](#核心特性)
- [典型场景](#典型场景)
- [架构与原理](#架构与原理)

---

## 简介

team4u-proxy 是一个高性能、易扩展的 Java 动态代理库。它旨在屏蔽底层动态代理技术（JDK Proxy 与 ByteBuddy）的复杂性，向外提供基于 **职责链模式 (Interceptor Chain)** 的 AOP 编程模型，以及极其易用的 **Fluent Builder API**。

与传统的代理实现不同，本组件将代理功能抽象为拦截器链，支持在运行时动态组合委托、追踪、空值安全和热交换等能力，是构建高弹性中间件系统的核心基石。

### 核心优势
* **双引擎智能驱动**：自动识别目标类型，无缝切换 JDK 代理（针对接口）与 ByteBuddy（针对普通类），彻底解决 CGLib 在 Java 高版本中的兼容性问题。
* **职责链 AOP 模型**：所有功能均为拦截器节点，支持自由排列组合，扩展性极强。
* **零 NPE 防御**：内置“空对象模式”，自动拦截链式调用并返回安全默认值，终结空指针异常。
* **线程安全热交换**：支持在不中断业务的情况下，动态替换代理背后的真实执行对象。
* **高性能反射缓存**：内置方法签名匹配与反射结果缓存，极大降低代理调用的性能开销。

---

## 快速入门

### 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-proxy</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

> [!NOTE]
> 本项目核心代理功能依赖于 [ByteBuddy](https://bytebuddy.net/)，已通过 Maven 自动引入。

### 基础用法：接口代理

```java
// 1. 定义接口
public interface UserService {
    String sayHello(String name);
}

// 2. 真实实现
UserService target = name -> "Hello, " + name;

// 3. 构建代理 (使用 forClass 指定接口，使用 delegate 设置委托)
UserService proxy = ProxyBuilder.forClass(UserService.class)
    .delegate(target)
    .build();

System.out.println(proxy.sayHello("World")); // 输出: Hello, World
```

### 进阶用法：类代理（非接口）

得益于类型自动推导，你可以使用 `forObject` 快速为普通类创建代理。

```java
public class OrderService {
    public void process(String id) { ... }
}

OrderService proxy = ProxyBuilder.forObject(new OrderService()).build();
```

### 极简用法：一键生成代理

如果你只需要挂载拦截器，可以使用静态快捷方法：

```java
UserService proxy = ProxyBuilder.proxy(new UserServiceImpl(), new MyInterceptor());
```

---

## 核心特性

### 委托转发 (Delegate)
这是最基础的功能。代理对象会将所有方法调用转发给指定的 `delegate` 对象。
* **鸭子类型支持**：即使 `delegate` 对象没有显式实现代理接口，只要它拥有相同签名（方法名+参数类型）的方法，即可完成转发。
* **性能优化**：内部使用 `ConcurrentHashMap` 缓存方法查找结果，避免重复反射。

```java
// 即使实现类没有显式实现接口 (implements UserService)
public class RawUserProcessor {
    public String sayHello(String name) {
        return "Duck: " + name;
    }
}

// 代理依然能将其方法映射到 UserService 接口上
UserService proxy = ProxyBuilder.forClass(UserService.class)
    .delegate(new RawUserProcessor())
    .build();
```

### 追踪与审计 (Track)
通过 `Tracker` 接口，你可以轻松实现日志记录、耗时统计或异常审计。

```java
UserService proxy = ProxyBuilder.forObject(target)
    .withTracker(new Tracker() {
        @Override
        public void before(Object proxy, Method method, Object[] args) {
            System.out.println("Calling: " + method.getName());
        }
        // ... 实现 after 和 onException
    })
    .build();
```


### 动态热交换 (HotSwap)
允许在应用运行期间，线程安全地替换底层的目标对象。

```java
UserService proxy = ProxyBuilder.forClass(UserService.class)
    .delegate(v1Instance)
    .enableHotswap() // 开启热交换能力
    .build();

// 切换版本
Swappable swappable = (Swappable) proxy;
swappable.hotswap(v2Instance); // 替换后，后续调用将进入 v2Instance
```

### 空对象模式 (Empty Object)
用于消除复杂的 `null` 检查，特别适合配置对象或级联查询场景。

```java
// 开启后，无需设置 delegate
AppConfig safeProxy = ProxyBuilder.forClass(AppConfig.class)
    .asEmptyObject()
    .build();

// 即使 getDb() 返回的是 null，这里也不会报 NPE
// 而是会返回一个嵌套的空代理对象，且其 getUrl() 返回 ""
String url = safeProxy.getDb().getUrl(); 
```

---

## 典型场景

### 1. 业务逻辑的热插拔
在灰度发布或 A/B 测试中，通过 `enableHotswap()` 创建代理，在不改变业务代码引用的情况下，根据实验指令切换不同的算法实现。

### 2. 健壮的配置管理
结合 `team4u-config`，使用 `asEmptyObject()` 代理配置接口。当配置项缺失时，业务代码仍能安全运行（返回默认值 0, false 或空集合），避免系统崩溃。

### 3. 轻量级监控切面
无需引入繁重的 Spring AOP，直接使用 `withTracker()` 即可为任何对象快速挂载性能监控和操作日志。

---

## 架构与原理

### 核心执行流程

`team4u-proxy` 的运行机制遵循 AOP 拦截器链模型：

1.  **构造上下文**：每次方法调用时，生成 `ReflectiveMethodInvocation` 实例。
2.  **推进职责链**：依次执行已注册的 `MethodInterceptor`（如 Track -> HotSwap -> Delegate）。
3.  **收口处理**：
    *   如果存在 `DelegateInterceptor`，则执行反射调用。
    *   如果是 `EmptyValueInterceptor`，则返回空代理。
    *   如果没有拦截器返回结果，则返回该类型的安全默认值（基础类型 0/false 等）。

### 引擎路由策略

`ProxyBuilder` 会根据配置自动选择最优引擎：
*   **JdkProxyEngine**：当主代理类型及所有附加接口均为 `Interface` 时使用。
*   **ByteBuddyProxyEngine**：当主代理类型为普通 `Class` 时使用，支持方法拦截和接口增强。

---

## 扩展性

你可以通过实现 `MethodInterceptor` 接口来开发自己的拦截器，并通过 `intercept()` 注入到职责链中，实现诸如权限校验、缓存拦截等自定义功能。

```java
public class MyAuthInterceptor implements MethodInterceptor {
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        if (!isAuthorized()) throw new UnauthorizedException();
        return invocation.proceed();
    }
}
```
