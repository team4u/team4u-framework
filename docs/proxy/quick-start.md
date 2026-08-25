# 快速开始

本文介绍如何在项目中快速引入并使用 `team4u-proxy`。

---

## 1. 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-proxy</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

> [!NOTE]
> `team4u-proxy` 内部已自动传递依赖 ByteBuddy，无需手动额外声明。

---

## 2. 基础用法：接口代理与委托

```java
import com.team4u.framework.proxy.ProxyBuilder;

// 1. 定义业务接口与底层目标
public interface UserService {
    String sayHello(String name);
}

UserService target = name -> "Hello, " + name;

// 2. 为接口创建代理并绑定委托对象
UserService proxy = ProxyBuilder.forClass(UserService.class)
        .delegate(target)
        .build();

System.out.println(proxy.sayHello("World")); // 输出: Hello, World
```

---

## 3. 进阶用法：普通类代理（非接口）

得益于 ByteBuddy 双引擎自适应机制，你可以直接为没有实现接口的普通类（非 `final` 类）创建代理：

```java
import com.team4u.framework.proxy.ProxyBuilder;

public class OrderService {
    public String process(String orderId) {
        return "Order processed: " + orderId;
    }
}

OrderService target = new OrderService();
OrderService proxy = ProxyBuilder.forClass(OrderService.class)
        .delegate(target)
        .build();

System.out.println(proxy.process("ORD_1001")); // 输出: Order processed: ORD_1001
```

---

## 4. 极简用法：一键挂载拦截器

使用静态快捷方法 `ProxyBuilder.proxy(...)`，一步到位生成包含指定拦截器的代理对象：

```java
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.core.MethodInterceptor;

UserService rawService = name -> "Hello, " + name;

MethodInterceptor logInterceptor = invocation -> {
    System.out.println("Before: " + invocation.getMethod().getName());
    Object result = invocation.proceed();
    System.out.println("After: " + invocation.getMethod().getName());
    return result;
};

UserService proxy = ProxyBuilder.proxy(rawService, logInterceptor);
proxy.sayHello("Jack");
```

---

## 下一步

- 了解鸭子类型委托与反射缓存：[鸭子类型委托与反射缓存](proxy-delegate.md)
- 探索方法执行追踪与审计：[调用链追踪与性能审计](proxy-track.md)
- 在线替换目标实例：[运行时热交换 (HotSwap)](proxy-hotswap.md)
- 消除级联调用空指针：[空对象模式防 NPE](proxy-empty.md)
- 编写高级切面与职责链：[自定义 AOP 拦截器链](proxy-interceptor.md)
