package com.team4u.framework.proxy.core;

import java.lang.reflect.Method;

/**
 * 统一的方法执行上下文
 *
 * @author team4u
 */
public interface MethodInvocation {

    /**
     * 获取代理对象实例本身
     */
    Object getProxy();

    /**
     * 获取当前被拦截执行的方法
     */
    Method getMethod();

    /**
     * 获取执行当前方法的实参列表
     */
    Object[] getArguments();

    /**
     * 核心逻辑：推进拦截器链至下一个节点。
     * 若已是最后一个节点，则根据情况返回默认值或抛出异常。
     *
     * @return 方法执行结果
     * @throws Throwable 执行异常
     */
    Object proceed() throws Throwable;
}
