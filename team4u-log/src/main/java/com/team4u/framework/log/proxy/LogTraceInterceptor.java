package com.team4u.framework.log.proxy;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;

import java.lang.reflect.Method;

/**
 * 自动日志追踪拦截器
 * <p>
 * 基于 team4u-proxy 实现，记录耗时、参数、返回值及异常信息。
 */
public class LogTraceInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        // 获取追踪配置
        AutoLogTrace config = getAnnotation(invocation, method);

        // 无配置则直接放行
        if (config == null) {
            return invocation.proceed();
        }

        Class<?> targetClass = LogTraceSupport.getTargetClass(invocation, method);
        String action = config.action().isEmpty() ? method.getName() : config.action();

        // 统一构建配置选项并执行
        LogTraceSupport.LogTraceOptions options = LogTraceSupport.LogTraceOptions.builder()
                .targetClass(targetClass)
                .action(action)
                .slowThreshold(config.slowThreshold())
                .ignoreExceptionClasses(config.ignoreExceptions())
                .build();

        return LogTraceSupport.proceed(invocation, options);
    }

    private AutoLogTrace getAnnotation(MethodInvocation invocation, Method method) {
        AutoLogTrace config = method.getAnnotation(AutoLogTrace.class);
        if (config != null) return config;

        Class<?> targetClass = LogTraceSupport.getTargetClass(invocation, method);
        if (targetClass != null && targetClass != Object.class) {
            try {
                Method originalMethod = targetClass.getDeclaredMethod(method.getName(), method.getParameterTypes());
                config = originalMethod.getAnnotation(AutoLogTrace.class);
                if (config != null) return config;
            } catch (NoSuchMethodException ignored) {
            }

            config = targetClass.getAnnotation(AutoLogTrace.class);
            if (config != null) return config;
        }

        return method.getDeclaringClass().getAnnotation(AutoLogTrace.class);
    }
}
