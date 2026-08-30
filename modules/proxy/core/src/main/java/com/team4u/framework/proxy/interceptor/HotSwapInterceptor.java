package com.team4u.framework.proxy.interceptor;

import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.proxy.support.Swappable;

import java.lang.reflect.Method;

/**
 * 热交换拦截器：支持运行时无缝、线程安全地替换委托对象
 *
 * @author jay.wu
 */
public class HotSwapInterceptor extends DelegateInterceptor {

    private static final Method HOTSWAP_METHOD;

    static {
        try {
            HOTSWAP_METHOD = Swappable.class.getMethod("hotswap", Object.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError("Swappable interface missing hotswap method");
        }
    }

    public HotSwapInterceptor(Object initialDelegate) {
        super(initialDelegate);
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        // 如果调用的是 hotswap 方法，拦截并执行替换逻辑
        if (isHotswapMethod(invocation.getMethod())) {
            return hotswap(invocation.getArguments()[0]);
        }

        // 否则执行正常的委托逻辑
        return super.invoke(invocation);
    }

    private boolean isHotswapMethod(Method method) {
        // 判断方法签名是否一致
        return method.getName().equals(HOTSWAP_METHOD.getName()) &&
                method.getParameterCount() == 1 &&
                method.getParameterTypes()[0] == Object.class;
    }

    /**
     * 线程安全的替换底层委托对象
     */
    private Object hotswap(Object newDelegate) {
        // 由于 delegate 在父类中被声明为 volatile，这里的替换对所有线程立即可见
        Object oldDelegate = this.delegate;
        this.delegate = newDelegate;
        return oldDelegate;
    }
}
