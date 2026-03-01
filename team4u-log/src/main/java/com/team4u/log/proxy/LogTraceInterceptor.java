package com.team4u.log.proxy;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.log.Loggers;
import com.team4u.log.mask.FastMasker;
import com.team4u.log.mask.config.MaskRuleRepository;

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
        // 将参数转换为命名的 Map，并执行主动脱敏
        Map<String, Object> namedArgs = buildNamedArguments(invocation, method, args);

        try {
            Object result = invocation.proceed();
            long cost = System.currentTimeMillis() - start;

            Loggers loggers = Loggers.of(method.getDeclaringClass())
                    .action(action)
                    .duration(cost)
                    .kvs(namedArgs)
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
            Throwable throwable = e;
            if (throwable instanceof java.lang.reflect.InvocationTargetException && throwable.getCause() != null) {
                throwable = throwable.getCause();
            }
            long cost = System.currentTimeMillis() - start;

            Loggers loggers = Loggers.of(method.getDeclaringClass())
                    .action(action)
                    .duration(cost)
                    .kvs(namedArgs);

            if (isIgnoredException(throwable, config.ignoreExceptions())) {
                loggers.atWarn()
                        .status("business_error")
                        .kv("errMsg", throwable.getMessage());
            } else {
                loggers.failed(throwable);
            }

            loggers.log();
            throw throwable;
        }
    }

    private Map<String, Object> buildNamedArguments(MethodInvocation invocation, Method method, Object[] args) {
        Map<String, Object> namedArgs = new LinkedHashMap<>();
        if (args == null || args.length == 0) {
            return namedArgs;
        }

        Class<?> targetClass = getTargetClass(invocation, method);
        Parameter[] parameters = getParameters(targetClass, method);

        for (int i = 0; i < args.length; i++) {
            Parameter parameter = (parameters != null && i < parameters.length) ? parameters[i] : null;
            // 只有当 parameter 不为 null 且参数名为真实名称（即保留了编译参数）时，才使用它；否则回退到 argX
            String paramName = (parameter != null && parameter.isNamePresent()) ? parameter.getName() : "arg" + i;
            Object value = args[i];

            // 主动触发脱敏
            if (value instanceof String) {
                String maskType = MaskRuleRepository.getInstance().findRule(targetClass.getName(), paramName);
                if (maskType != null) {
                    value = FastMasker.mask((String) value, maskType);
                }
            }

            namedArgs.put(paramName, value);
        }
        return namedArgs;
    }

    private Class<?> getTargetClass(MethodInvocation invocation, Method method) {
        Object proxy = invocation.getProxy();
        if (proxy == null) {
            return method.getDeclaringClass();
        }

        Class<?> proxyClass = proxy.getClass();
        if (proxyClass.getName().contains("ByteBuddy") || proxyClass.getName().contains("$$")) {
            return proxyClass.getSuperclass();
        }

        return proxyClass;
    }

    private Parameter[] getParameters(Class<?> targetClass, Method method) {
        // 优先从原始目标类中查找方法并获取参数名
        try {
            Method targetMethod = targetClass.getDeclaredMethod(method.getName(), method.getParameterTypes());
            Parameter[] parameters = targetMethod.getParameters();
            if (parameters.length > 0 && parameters[0].isNamePresent()) {
                return parameters;
            }
        } catch (NoSuchMethodException ignored) {
        }

        // 如果目标类没拿到，尝试直接使用传入的 method (ByteBuddy 代理有时会保留父类参数名)
        Parameter[] parameters = method.getParameters();
        if (parameters.length > 0 && parameters[0].isNamePresent()) {
            return parameters;
        }

        return null;
    }

    private AutoLogTrace getAnnotation(MethodInvocation invocation, Method method) {
        AutoLogTrace config = method.getAnnotation(AutoLogTrace.class);
        if (config != null) return config;

        Class<?> targetClass = getTargetClass(invocation, method);
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
