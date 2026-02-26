package com.team4u.framework.proxy.core;

/**
 * 统一的方法拦截器接口 (AOP 环绕增强)
 *
 * @author team4u
 */
public interface MethodInterceptor {

    /**
     * 处理拦截逻辑
     *
     * @param invocation 方法执行上下文
     * @return 方法返回值
     * @throws Throwable 执行过程中产生的异常
     */
    Object invoke(MethodInvocation invocation) throws Throwable;
}
