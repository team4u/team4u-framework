package com.team4u.log.proxy;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.log.Loggers;

import java.lang.reflect.Method;

import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

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

        long start = System.currentTimeMillis();
        String action = config.action().isEmpty() ? method.getName() : config.action();
        Object[] args = invocation.getArguments();
        // 将参数转换为命名的 Map，以便触发 Jackson 字段脱敏
        Map<String, Object> namedArgs = buildNamedArguments(method, args);

        try {
            Object result = invocation.proceed();
            long cost = System.currentTimeMillis() - start;

            Loggers loggers = Loggers.of(method.getDeclaringClass())
                    .action(action)
                    .duration(cost)
                    .kv("req", namedArgs)
                    .kv("resp", result);

            if (config.slowThreshold() > 0 && cost > config.slowThreshold()) {
                loggers.atWarn()
                        .status("slow_success")
                        .kv("slowThreshold", config.slowThreshold());
            } else {
                loggers.success();
            }

            loggers.log();
            return result;

        } catch (Throwable e) {
            if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
                e = e.getCause();
            }
            long cost = System.currentTimeMillis() - start;

            Loggers loggers = Loggers.of(method.getDeclaringClass())
                    .action(action)
                    .duration(cost)
                    .kv("req", namedArgs);

            if (isIgnoredException(e, config.ignoreExceptions())) {
                loggers.atWarn()
                        .status("business_error")
                        .kv("errMsg", e.getMessage());
            } else {
                loggers.failed(e);
            }

            loggers.log();
            throw e;
        }
    }

    private Map<String, Object> buildNamedArguments(Method method, Object[] args) {
        Map<String, Object> namedArgs = new LinkedHashMap<>();
        if (args == null || args.length == 0) {
            return namedArgs;
        }

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < args.length; i++) {
            // 获取参数名，若未开启 -parameters 编译参数则回退到 arg0, arg1...
            String paramName = (i < parameters.length) ? parameters[i].getName() : "arg" + i;
            namedArgs.put(paramName, args[i]);
        }
        return namedArgs;
    }

    private AutoLogTrace getAnnotation(MethodInvocation invocation, Method method) {
        // 1. 直接从方法获取
        AutoLogTrace config = method.getAnnotation(AutoLogTrace.class);
        if (config != null) return config;

        // 2. 尝试从目标类的同名方法获取
        Class<?> targetClass = invocation.getProxy().getClass().getSuperclass();
        if (targetClass != null && targetClass != Object.class) {
            try {
                Method originalMethod = targetClass.getDeclaredMethod(method.getName(), method.getParameterTypes());
                config = originalMethod.getAnnotation(AutoLogTrace.class);
                if (config != null) return config;
            } catch (NoSuchMethodException ignored) {
            }

            // 3. 从目标类级别获取
            config = targetClass.getAnnotation(AutoLogTrace.class);
            if (config != null) return config;
        }

        // 4. 从当前方法声明类获取
        return method.getDeclaringClass().getAnnotation(AutoLogTrace.class);
    }

    private boolean isIgnoredException(Throwable e, Class<? extends Throwable>[] ignores) {
        if (ignores == null) {
            return false;
        }
        for (Class<? extends Throwable> ignore : ignores) {
            if (ignore.isAssignableFrom(e.getClass())) {
                return true;
            }
        }
        return false;
    }
}
