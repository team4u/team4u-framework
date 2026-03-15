package com.team4u.framework.log.proxy;

import java.lang.reflect.Method;

/**
 * 统一的方法调用抽象
 */
public interface LogInvocation {

    /**
     * 获取当前代理对象；若无则返回 null
     */
    default Object getProxy() {
        return null;
    }

    /**
     * 获取目标对象；若无则返回 null
     */
    Object getTarget();

    /**
     * 获取当前执行的方法
     */
    Method getMethod();

    /**
     * 获取当前方法参数
     */
    Object[] getArguments();

    /**
     * 推进调用链
     */
    Object proceed() throws Throwable;
}
