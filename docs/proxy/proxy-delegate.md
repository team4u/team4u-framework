# 鸭子类型委托与反射缓存

`team4u-proxy` 的委托机制（`withDelegate` / `delegate`）支持将方法调用转发给任何兼容的目标对象，即使目标对象没有显式实现被代理的接口（即“**鸭子类型 (Duck Typing)**”——只要走起来像鸭子，叫起来像鸭子，它就是鸭子）。

---

## 鸭子类型委托示例

假设你引入了一个第三方库中的类 `ThirdPartyUserProcessor`，它未实现你的 `UserService` 接口，但具备同名同参的方法：

```java
import com.team4u.framework.proxy.ProxyBuilder;

// 第三方类：未实现 UserService 接口
public class ThirdPartyUserProcessor {
    public String sayHello(String name) {
        return "Duck Typing: " + name;
    }
}

// 业务定义的标准接口
public interface UserService {
    String sayHello(String name);
}

// 创建代理：框架自动将接口方法映射到目标对象的同名同参方法上
UserService proxy = ProxyBuilder.forClass(UserService.class)
        .delegate(new ThirdPartyUserProcessor())
        .build();

System.out.println(proxy.sayHello("Jack")); // 输出: Duck Typing: Jack
```

---

## 内部实现与性能优化

`DelegateInterceptor` 在执行委托转发时，采用了以下核心机制：

### 1. 继承/实现关系直接调用
如果目标对象的类型本身是被代理类型的子类或实现类（`method.getDeclaringClass().isAssignableFrom(target.getClass())`），则直接使用原始 `Method` 对象执行调用，避免任何额外查找开销。

### 2. 鸭子类型动态匹配与并发缓存
如果目标对象没有实现该接口，`DelegateInterceptor` 通过 `ReflectUtil.getMethod(target.getClass(), methodName, paramTypes)` 进行同名同签名的方法查找，并将映射关系缓存在 `ConcurrentMap<Method, Method> methodCache` 中。
- 首次调用完成解析并写入缓存；
- 后续所有并发调用均为纳秒级的 Map 查找与反射执行。

### 3. 业务异常多层自动解包
在反射调用中，业务抛出的异常通常会被包裹在 `InvocationTargetException` 或工具类运行时异常中。`DelegateInterceptor` 会递归剥离这些包装异常：
```java
Throwable cause = e;
while (cause.getCause() != null &&
        (cause instanceof InvocationTargetException
                || cause.getClass().getName().contains("InvocationTargetRuntimeException")
                || cause.getClass().getName().contains("UtilException"))) {
    cause = cause.getCause();
    if (cause instanceof InvocationTargetException) {
        cause = ((InvocationTargetException) cause).getTargetException();
    }
}
throw cause;
```
这确保了调用方捕获到的是业务真实抛出的原始异常（如 `BizException`），而无需在外层手动做复杂的 `getCause()` 解包。
