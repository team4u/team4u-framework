package com.team4u.framework.proxy.support;

import java.lang.reflect.Method;

/**
 * 方法执行追踪器接口
 * <p>
 * 用于监听代理对象方法的完整生命周期。通过实现此接口，可以在方法执行的前、后
 * 以及发生异常时插入自定义逻辑，常用于审计日志、性能监控、权限校验等切面场景。
 * </p>
 *
 * @author team4u
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
     * @param result 方法执行的返回值（如果方法返回类型为 void，则该值为 null）
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
