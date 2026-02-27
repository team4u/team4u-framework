package com.team4u.framework.router.proxy;

import cn.hutool.core.annotation.AnnotationUtil;
import cn.hutool.core.util.ArrayUtil;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.router.proxy.annotation.RouteContext;
import com.team4u.framework.router.proxy.annotation.Routed;
import lombok.Data;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 声明式路由方法拦截器
 * <p>
 * 核心逻辑：拦截接口方法调用 -> 提取路由上下文 -> 执行路由得到目标 Bean -> 反射调用。
 * </p>
 *
 * @author jay.wu
 */
public class RoutedMethodInterceptor implements MethodInterceptor {

    /**
     * 方法元数据缓存，确保 O(1) 的超高性能
     */
    private final Map<Method, RouteMetadata> metadataCache = new ConcurrentHashMap<>();

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        // 1. 获取或解析方法路由元数据
        RouteMetadata metadata = metadataCache.computeIfAbsent(method, this::parseMetadata);

        // 如果该方法没有 @Routed 注解，直接放行（调用原逻辑）
        if (!metadata.isRouted()) {
            return invocation.proceed();
        }

        // 2. 提取路由上下文
        Object context = extractContext(invocation.getArguments(), metadata.getContextParamIndex());

        // 3. 使用 Locator 动态查找真正的目标 Bean
        // 注意：这里期望的类型就是当前方法所在的接口类
        Object targetBean = RoutedBeanLocator.locate(
                metadata.getRouterId(),
                context,
                method.getDeclaringClass());

        // 4. 动态分派：将方法调用转发给真正匹配的目标 Bean
        try {
            return method.invoke(targetBean, invocation.getArguments());
        } catch (InvocationTargetException e) {
            // 剥离反射包装的异常，抛出业务真实异常
            throw e.getTargetException();
        }
    }

    /**
     * 提取路由上下文
     */
    private Object extractContext(Object[] args, int contextIndex) {
        if (ArrayUtil.isEmpty(args)) {
            return null;
        }
        // 如果明确标注了 @RouteContext，使用该参数
        if (contextIndex >= 0 && contextIndex < args.length) {
            return args[contextIndex];
        }
        // 默认兜底：如果没有标注，直接使用第一个参数作为上下文
        return args[0];
    }

    /**
     * 解析方法元数据（仅在方法首次被调用时执行一次）
     */
    private RouteMetadata parseMetadata(Method method) {
        // 优先找方法上的注解，再找类上的注解
        Routed routed = AnnotationUtil.getAnnotation(method, Routed.class);
        if (routed == null) {
            routed = AnnotationUtil.getAnnotation(method.getDeclaringClass(), Routed.class);
        }

        if (routed == null) {
            return new RouteMetadata(false, null, -1);
        }

        // 寻找哪个参数被标记了 @RouteContext
        int contextIndex = -1;
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < parameterAnnotations.length; i++) {
            for (Annotation ann : parameterAnnotations[i]) {
                if (ann instanceof RouteContext) {
                    contextIndex = i;
                    break;
                }
            }
        }

        return new RouteMetadata(true, routed.routerId(), contextIndex);
    }

    /**
     * 内部类：缓存解析结果，避免重复反射
     */
    @Data
    private static class RouteMetadata {
        private final boolean routed;
        private final String routerId;
        private final int contextParamIndex;
    }
}
