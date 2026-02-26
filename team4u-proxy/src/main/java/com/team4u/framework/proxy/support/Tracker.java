package com.team4u.framework.proxy.support;

import java.lang.reflect.Method;

/**
 * 追踪器接口
 *
 * @author team4u
 */
public interface Tracker {
    void before(Object proxy, Method method, Object[] args);

    void after(Object proxy, Method method, Object[] args, Object result);

    void onException(Object proxy, Method method, Object[] args, Throwable e);
}
