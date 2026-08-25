# 调用链追踪与性能审计 (Tracker)

通过 `Tracker` 接口与 `TrackInterceptor`，无需引入庞大的 Spring AOP 或字节码 Instrumentation，即可快速为任意普通对象或接口挂载轻量级的方法生命周期监听、耗时审计与异常捕获。

---

## `Tracker` 接口定义

```java
package com.team4u.framework.proxy.support;

import java.lang.reflect.Method;

/**
 * 方法执行追踪器接口
 */
public interface Tracker {

    /**
     * 方法执行前的回调
     *
     * @param proxy  代理对象实例
     * @param method 当前调用的方法对象
     * @param args   方法执行的实参列表
     */
    void before(Object proxy, Method method, Object[] args);

    /**
     * 方法正常执行完成后的回调
     *
     * @param proxy  代理对象实例
     * @param method 当前调用的方法对象
     * @param args   方法执行的实参列表
     * @param result 方法执行的返回值（void 方法则为 null）
     */
    void after(Object proxy, Method method, Object[] args, Object result);

    /**
     * 方法执行抛出异常时的回调
     *
     * @param proxy  代理对象实例
     * @param method 当前调用的方法对象
     * @param args   方法执行的实参列表
     * @param e      执行过程中抛出的原始异常对象
     */
    void onException(Object proxy, Method method, Object[] args, Throwable e);
}
```

---

## 拦截器执行逻辑 (`TrackInterceptor`)

```java
package com.team4u.framework.proxy.interceptor;

public class TrackInterceptor implements MethodInterceptor {
    private final Tracker tracker;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object proxy = invocation.getProxy();

        tracker.before(proxy, invocation.getMethod(), invocation.getArguments());

        try {
            Object result = invocation.proceed();
            tracker.after(proxy, invocation.getMethod(), invocation.getArguments(), result);
            return result;
        } catch (Throwable e) {
            tracker.onException(proxy, invocation.getMethod(), invocation.getArguments(), e);
            throw e; // 确保异常继续向上抛出，不掩盖业务异常
        }
    }
}
```

---

## 使用示例：方法执行监控与慢调用告警

```java
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.support.Tracker;
import java.lang.reflect.Method;

UserService rawService = new UserServiceImpl();

UserService proxy = ProxyBuilder.forObject(rawService)
        .withTracker(new Tracker() {
            private final ThreadLocal<Long> startTimes = new ThreadLocal<>();

            @Override
            public void before(Object proxy, Method method, Object[] args) {
                startTimes.set(System.currentTimeMillis());
                System.out.printf("[Audit] 开始执行方法: %s, 入参个数: %d%n", method.getName(), args.length);
            }

            @Override
            public void after(Object proxy, Method method, Object[] args, Object result) {
                long duration = System.currentTimeMillis() - startTimes.get();
                startTimes.remove();
                System.out.printf("[Audit] 方法执行完成: %s, 耗时: %d ms, 返回值: %s%n", 
                        method.getName(), duration, result);
            }

            @Override
            public void onException(Object proxy, Method method, Object[] args, Throwable e) {
                startTimes.remove();
                System.err.printf("[Audit] 方法抛出异常: %s, 异常信息: %s%n", 
                        method.getName(), e.getMessage());
            }
        })
        .build();

proxy.sayHello("Tom");
```
