# 自定义 AOP 拦截器链

`team4u-proxy` 内部基于责任链模式设计。开发者可以通过实现 `MethodInterceptor` 接口编写自定义拦截器，挂载权限校验、参数篡改、缓存拦截、重试或限流等切面逻辑。

---

## 核心接口契约

### `MethodInterceptor`
```java
package com.team4u.framework.proxy.core;

public interface MethodInterceptor {
    /**
     * 执行拦截逻辑
     *
     * @param invocation 方法调用上下文
     * @return 方法返回值
     * @throws Throwable 业务或切面异常
     */
    Object invoke(MethodInvocation invocation) throws Throwable;
}
```

### `MethodInvocation`
```java
package com.team4u.framework.proxy.core;

import java.lang.reflect.Method;

public interface MethodInvocation {
    /** 获取代理对象自身 */
    Object getProxy();

    /** 获取底层目标对象（若无委托目标则为 null） */
    Object getTarget();

    /** 获取当前正在调用的 Method 对象 */
    Method getMethod();

    /** 获取当前调用的实参列表 */
    Object[] getArguments();

    /** 推进执行下一个拦截器或最终目标方法 */
    Object proceed() throws Throwable;
}
```

---

## 职责链推进与可重放机制 (`ReflectiveMethodInvocation`)

`ReflectiveMethodInvocation` 负责维护当前执行的游标索引 `currentInterceptorIndex`：

```java
@Override
public Object proceed() throws Throwable {
    // 拦截器链已到达末尾，执行默认收尾或终止逻辑
    if (this.currentInterceptorIndex == this.interceptors.size() - 1) {
        return invokeJoinPoint();
    }

    // 获取下一个拦截器并执行
    MethodInterceptor interceptor = this.interceptors.get(++this.currentInterceptorIndex);
    try {
        return interceptor.invoke(this);
    } finally {
        // 关键设计：无论成功或失败都回退游标！
        // 这使得外层的重试拦截器可以多次调用 proceed() 重新走一遍下游拦截器链
        this.currentInterceptorIndex--;
    }
}
```

### 链尾收尾逻辑 (`invokeJoinPoint`)
当没有委托对象或所有拦截器均已推进完毕时：
- `toString()`：返回 "Proxy[" + simpleName + "]@" + Integer.toHexString(identityHashCode)`；
- `hashCode()`：返回 `System.identityHashCode(proxy)`；
- `equals(other)`：返回 `proxy == arguments[0]`；
- `void` 返回类型：返回 `null`；
- 基础数据类型：安全返回对应类型的零值（如 `false`, `0`, `0L`, `0.0d`, `'\0'`）。

---

## 编写自定义拦截器示例

### 案例：简易方法权限校验拦截器
```java
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.proxy.ProxyBuilder;

public class AdminAuthInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        String methodName = invocation.getMethod().getName();

        // 仅对 delete 开头的方法做权限校验
        if (methodName.startsWith("delete")) {
            boolean isAdmin = checkCurrentRoleIsAdmin();
            if (!isAdmin) {
                throw new SecurityException("无权执行敏感删除操作: " + methodName);
            }
        }

        // 推进责任链向下执行
        return invocation.proceed();
    }

    private boolean checkCurrentRoleIsAdmin() {
        // 校验当前线程安全上下文...
        return false;
    }
}
```

### 挂载拦截器：
```java
UserService service = new UserServiceImpl();

UserService proxy = ProxyBuilder.forClass(UserService.class)
        .delegate(service)
        .addInterceptor(new AdminAuthInterceptor())
        .build();

// 执行普通查询正常通过
proxy.queryUser("U1001");

// 执行敏感删除抛出 SecurityException
proxy.deleteUser("U1001");
```

---

## 拦截器链组装顺序

通过 `ProxyBuilder` 组装拦截器链时，执行顺序遵循先进先出（FIFO）：
1. 自定义拦截器（按 `addInterceptor` / `intercept` 添加的先后顺序）；
2. 追踪拦截器（`withTracker` 添加的 `TrackInterceptor`）；
3. 尾部收口拦截器：
   - 若开启 `asEmptyObject()`，尾部为 `EmptyValueInterceptor`；
   - 若开启 `enableHotswap()`，尾部为 `HotSwapInterceptor`；
   - 否则尾部为 `DelegateInterceptor`。
