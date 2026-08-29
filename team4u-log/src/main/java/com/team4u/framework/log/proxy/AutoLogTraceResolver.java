package com.team4u.framework.log.proxy;

import com.team4u.framework.proxy.support.AnnotatedMethodResolver;

import java.lang.reflect.Method;

/**
 * 统一解析 {@link AutoLogTrace} 的位置
 * <p>
 * 注解查找逻辑收编至 team4u-proxy 的 {@link AnnotatedMethodResolver}
 * （支持 targetClass 继承体系最具体方法、桥接方法还原、接口链与类级注解查找，并带缓存）。
 */
public final class AutoLogTraceResolver {

    /**
     * 全局共享的注解解析器（解析结果按 (method, targetClass) 缓存）
     */
    private static final AnnotatedMethodResolver<AutoLogTrace> RESOLVER =
            AnnotatedMethodResolver.of(AutoLogTrace.class);

    private AutoLogTraceResolver() {
    }

    /**
     * 解析方法上生效的 {@link AutoLogTrace} 注解
     *
     * @param targetClass 具体的执行目标类型
     * @param method      拦截到的方法
     * @return 生效的注解实例；未找到时返回 null
     */
    public static AutoLogTrace resolve(Class<?> targetClass, Method method) {
        return RESOLVER.resolve(method, targetClass);
    }
}
