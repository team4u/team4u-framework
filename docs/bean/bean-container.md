# 本地容器与原子懒加载

`LocalBeanContainer` 是 `BeanManager` 的默认底层存储，基于 `ConcurrentHashMap` 实现了 $O(1)$ 的无锁并发读取与线程安全注册。

---

## 核心接口契约 (ISP)

`team4u-bean` 遵循接口隔离原则，严格分离读写权限：

### 1. `BeanFactory`（只读检索契约）
```java
package com.team4u.framework.bean.core;

import java.util.Map;

public interface BeanFactory {
    /** 根据名称获取 Bean，不存在返回 null */
    <T> T getBean(String name);

    /** 根据 Class 类型获取首个匹配的 Bean，不存在返回 null */
    <T> T getBean(Class<T> type);

    /** 获取指定类型的所有 Bean 映射 (beanName -> beanInstance) */
    <T> Map<String, T> getBeansOfType(Class<T> type);

    /** 获取当前工厂在 BeanManager 链中的优先级顺序，数值越小优先级越高 */
    int getOrder();
}
```

### 2. `BeanRegistry`（写入注册契约）
```java
package com.team4u.framework.bean.core;

public interface BeanRegistry {
    /** 以指定名称向容器注册单例 Bean，若名称已存在则返回 false */
    <T> boolean registerBean(String beanName, T bean);

    /** 以类全限定名向容器注册单例 Bean */
    <T> boolean registerBean(T bean);
}
```

---

## 本地容器实现细节 (`LocalBeanContainer`)

- **底层数据结构**：`ConcurrentHashMap<String, Object> singletonObjects`；
- **优先级**：`getOrder()` 返回 `Integer.MAX_VALUE`，确保作为整个查找链条的最终兜底；
- **并发注册与去重**：使用 `singletonObjects.putIfAbsent(beanName, bean)` 确保在多线程并发竞争注册同一名称的 Bean 时，仅有一方注册成功；
- **初始化事件通知**：在注册成功后，自动通过 `EventDispatcher.publish(new BeanInitializedEvent(beanName, bean))` 触发就绪广播。

---

## 原子懒加载机制 (`BeanManager.loadBean`)

在许多高性能组件与中间件中，某些 Bean（如数据库连接池、Netty Client、规则缓存引擎）初始化耗时极高。我们希望实现“**首次实际访问时才初始化，且在多线程高并发访问下安全初始化一次**”。

```java
import com.team4u.framework.bean.BeanManager;

MyHeavyService service = BeanManager.getInstance().loadBean(
        MyHeavyService.class, 
        () -> {
            // 昂贵的初始化逻辑
            return new MyHeavyServiceImpl();
        }
);
```

### 执行逻辑：
1. 优先调用 `getBean(type)` 遍历所有已注册的 `BeanFactory`（包括 Spring 容器）；
2. 若已存在对应 Bean，直接返回现有实例，不触发 `Supplier`；
3. 若容器中不存在，执行 `Supplier.get()` 创建新实例，并通过 `localContainer.registerBean(newBean)` 注册到本地容器中并返回。
