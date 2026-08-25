# 运行时热交换 (HotSwap)

在服务平滑发布、A/B 实验动态切流、配置热更新或故障快速降级场景中，`team4u-proxy` 允许在**不中断业务调用、不修改业务引用**的前提下，动态原子替换代理背后的真实执行对象。

---

## 启用热交换能力 (`enableHotswap`)

在构建代理时，链式调用 `.enableHotswap()`。框架会自动为代理类附加实现 `Swappable` 接口，并挂载 `HotSwapInterceptor`：

```java
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.support.Swappable;

// 1. 定义两个不同的实现版本
UserService v1Service = name -> "[V1] Hello, " + name;
UserService v2Service = name -> "[V2] Hello, " + name;

// 2. 构建支持热交换的代理对象（初始绑定 V1 实现）
UserService proxy = ProxyBuilder.forClass(UserService.class)
        .delegate(v1Service)
        .enableHotswap()
        .build();

// 3. 初始调用（执行 V1 逻辑）
System.out.println(proxy.sayHello("Alice")); // 输出: [V1] Hello, Alice

// 4. 动态热替换：强转为 Swappable 接口并调用 hotswap
Swappable swappable = (Swappable) proxy;
Object oldService = swappable.hotswap(v2Service); // 返回旧的 v1Service

// 5. 后续调用（立即无缝切换至 V2 逻辑）
System.out.println(proxy.sayHello("Alice")); // 输出: [V2] Hello, Alice
```

---

## 核心实现与线程安全保障

### `Swappable` 契约
```java
package com.team4u.framework.proxy.support;

public interface Swappable {
    /**
     * 替换底层的真实委托对象
     *
     * @param newDelegate 新的委托对象
     * @return 替换下来的旧委托对象
     */
    Object hotswap(Object newDelegate);
}
```

### `HotSwapInterceptor` 拦截逻辑
`HotSwapInterceptor` 继承自 `DelegateInterceptor`：
- 当检测到调用的方法是 `Swappable.hotswap(Object)` 时，直接拦截并执行字段替换：
  ```java
  private Object hotswap(Object newDelegate) {
      Object oldDelegate = this.delegate;
      this.delegate = newDelegate;
      return oldDelegate;
  }
  ```
- 底层的 `delegate` 字段声明为 `protected volatile Object delegate`，由 JVM 内存模型的 `volatile` 写屏障保证对所有线程立即可见；
- 并发调用在切换瞬间要么完整执行旧实例，要么完整执行新实例，天然消除中间悬挂与脏状态。
