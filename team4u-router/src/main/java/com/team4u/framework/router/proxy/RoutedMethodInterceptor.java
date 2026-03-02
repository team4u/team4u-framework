package com.team4u.framework.router.proxy;

import cn.hutool.core.annotation.AnnotationUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ClassUtil;
import com.team4u.framework.base.util.TextTemplate;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.exception.RouteConfigException;
import com.team4u.framework.router.proxy.annotation.RouteContext;
import com.team4u.framework.router.proxy.annotation.Routed;
import lombok.Data;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 声明式路由方法拦截器
 * <p>
 * 核心逻辑：拦截接口方法调用 -> 提取路由上下文 -> 执行路由得到目标 Bean -> 反射调用。
 * </p>
 *
 * @author jay.wu
 */
public class RoutedMethodInterceptor implements MethodInterceptor {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * 方法元数据缓存，确保 O(1) 的超高性能
     */
    private static final Map<Method, RouteMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    /**
     * 全局默认拦截器实例
     */
    private static final RoutedMethodInterceptor GLOBAL = new RoutedMethodInterceptor();
    private final RoutingManager routingManager;

    /**
     * 使用全局默认的路由管理器构建拦截器
     */
    public RoutedMethodInterceptor() {
        this(null);
    }

    /**
     * 使用自定义的路由管理器构建拦截器
     *
     * @param routingManager 自定义路由管理器
     */
    public RoutedMethodInterceptor(RoutingManager routingManager) {
        this.routingManager = routingManager != null ? routingManager : RoutingManager.global();
    }

    /**
     * 获取全局拦截器实例
     */
    public static RoutedMethodInterceptor global() {
        return GLOBAL;
    }

    /**
     * 解析方法元数据（仅在方法首次被调用时执行一次）
     */
    private static RouteMetadata parseMetadata(Method method) {
        // 优先找方法上的注解，再找类上的注解
        Routed routed = AnnotationUtil.getAnnotation(method, Routed.class);
        if (routed == null) {
            routed = AnnotationUtil.getAnnotation(method.getDeclaringClass(), Routed.class);
        }

        if (routed == null) {
            return new RouteMetadata(false, null, -1, 0, null);
        }

        String routerIdPattern = routed.routerId();

        // 预解析模板
        TextTemplate template = new TextTemplate(routerIdPattern);

        // 寻找哪个参数被标记了 @RouteContext
        int contextIndex = -1;
        int routeContextCount = 0;
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < parameterAnnotations.length; i++) {
            for (Annotation ann : parameterAnnotations[i]) {
                if (ann instanceof RouteContext) {
                    routeContextCount++;
                    contextIndex = i;
                    break;
                }
            }
        }

        if (routeContextCount > 1) {
            throw RouteConfigException.validationError(
                    "Multiple @RouteContext parameters found in method: " + method);
        }

        int placeholderCount = countPlaceholders(routerIdPattern);

        return new RouteMetadata(true, template, contextIndex, placeholderCount, routerIdPattern);
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        // 获取或解析方法路由元数据
        RouteMetadata metadata = METADATA_CACHE.computeIfAbsent(method, RoutedMethodInterceptor::parseMetadata);

        // 如果该方法没有 @Routed 注解，直接放行（调用原逻辑）
        if (!metadata.isRouted()) {
            return invocation.proceed();
        }

        // 提取路由上下文
        Object context = extractContext(invocation.getArguments(), metadata.getContextParamIndex());

        boolean simpleContext = context != null && ClassUtil.isSimpleValueType(context.getClass());
        if (simpleContext && metadata.getPlaceholderCount() > 1) {
            throw RouteConfigException.validationError(
                    "Simple @RouteContext only supports one placeholder in routerId pattern: "
                            + metadata.getRouterIdPattern());
        }

        // 解析真实的路由 ID：通过 Lambda 桥接 BeanUtil 和 TextTemplate
        String routerId = metadata.getTemplate().render(prop -> {
            if (context == null) {
                return null;
            }

            // 如果是简单类型或 String，仅允许单占位符模板
            if (simpleContext) {
                return String.valueOf(context);
            }

            return BeanUtil.getProperty(context, prop);
        });

        // 使用 Locator 动态查找真正的目标 Bean
        // 注意：这里期望的类型就是当前方法所在的接口类
        Object targetBean = RoutedBeanLocator.locate(
                this.routingManager,
                routerId,
                context,
                method.getDeclaringClass());

        // 动态分派：将方法调用转发给真正匹配的目标 Bean
        try {
            return method.invoke(targetBean, invocation.getArguments());
        } catch (InvocationTargetException e) {
            // 剥离反射包装的异常，抛出业务真实异常
            throw e.getTargetException();
        }
    }

    private static int countPlaceholders(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(pattern);
        while (matcher.find()) {
            count++;
        }
        return count;
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
     * 内部类：缓存解析结果，避免重复反射
     */
    @Data
    private static class RouteMetadata {
        private final boolean routed;
        private final TextTemplate template;
        private final int contextParamIndex;
        private final int placeholderCount;
        private final String routerIdPattern;
    }
}
