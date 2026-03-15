package com.team4u.framework.log.proxy;

import com.team4u.framework.base.util.ReflectUtil;
import com.team4u.framework.log.Loggers;
import com.team4u.framework.mask.FastMasker;
import com.team4u.framework.mask.config.MaskRuleRepository;
import com.team4u.framework.proxy.core.MethodInvocation;
import lombok.Builder;
import lombok.Data;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志追踪辅助支持类
 * <p>
 * 统一处理日志代理拦截器（注解驱动或配置驱动）中的核心逻辑：
 * 1. 耗时记录与异常解包
 * 2. 参数名提取与主动脱敏
 * 3. 慢日志判定与异常状态标记
 */
public class LogTraceSupport {

    /**
     * 执行拦截并记录日志
     *
     * @param invocation 拦截器方法调用上下文
     * @param options    日志追踪配置选项
     * @return 方法执行结果
     * @throws Throwable 原始业务异常
     */
    public static Object proceed(MethodInvocation invocation, LogTraceOptions options) throws Throwable {
        long startNanos = System.nanoTime();
        Method method = invocation.getMethod();
        Object[] args = invocation.getArguments();
        Class<?> targetClass = options.getTargetClass() != null ? options.getTargetClass() : getTargetClass(invocation, method);

        // 统一构建命名的参数 Map，并执行主动脱敏
        Map<String, Object> namedArgs = buildNamedArguments(targetClass, method, args);

        try {
            Object result = invocation.proceed();
            long cost = (System.nanoTime() - startNanos) / 1_000_000;

            Loggers loggers = Loggers.of(targetClass)
                    .action(options.getAction())
                    .duration(cost)
                    .putAll(namedArgs)
                    .put("resp", result);

            // 判定慢日志状态
            if (options.getSlowThreshold() > 0 && cost > options.getSlowThreshold()) {
                loggers.atWarn()
                        .status("slow_success")
                        .put("slowThreshold", options.getSlowThreshold());
            } else {
                loggers.success();
            }

            loggers.log();
            return result;

        } catch (Throwable e) {
            Throwable throwable = unwrap(e);
            long cost = (System.nanoTime() - startNanos) / 1_000_000;

            Loggers loggers = Loggers.of(targetClass)
                    .action(options.getAction())
                    .duration(cost)
                    .putAll(namedArgs);

            // 判定是否为需降级的业务异常
            if (isIgnoredException(throwable, options)) {
                loggers.atWarn()
                        .status("business_error")
                        .put("errMsg", throwable.getMessage());
            } else {
                loggers.failed(throwable);
            }

            loggers.log();
            throw throwable;
        }
    }

    /**
     * 构建命名的参数 Map，并根据规则执行主动脱敏
     */
    private static Map<String, Object> buildNamedArguments(Class<?> targetClass, Method method, Object[] args) {
        Map<String, Object> namedArgs = new LinkedHashMap<>();
        if (args == null || args.length == 0) {
            return namedArgs;
        }

        Parameter[] parameters = ReflectUtil.getParameters(targetClass, method);

        for (int i = 0; i < args.length; i++) {
            Parameter parameter = (parameters != null && i < parameters.length) ? parameters[i] : null;
            // 获取真实参数名，若无则回退至 argX
            String paramName = (parameter != null && parameter.isNamePresent()) ? parameter.getName() : "arg" + i;
            Object value = args[i];

            // 针对简单字符串类型执行主动脱敏（补齐 Jackson 无法对集合内原子项处理的短板）
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

    /**
     * 获取原始目标类
     * <p>
     * 针对AOP代理对象进行解包解析，支持兼容ByteBuddy及常规CGLib动态代理。
     * 当传递代理对象时，可以自动分析并穿透代理获取底层的真实业务类，
     * 用于确保日志和脱敏规则精确匹配到定义类上。
     *
     * @param invocation 拦截器方法调用上下文
     * @param method     当前执行的方法
     * @return 实际承担业务逻辑的原始类对象
     */
    public static Class<?> getTargetClass(MethodInvocation invocation, Method method) {
        if (invocation != null) {
            Object target = invocation.getTarget();
            if (target != null) {
                return userClass(target.getClass());
            }

            Object proxy = invocation.getProxy();
            if (proxy != null) {
                Class<?> proxyClass = userClass(proxy.getClass());
                if (proxyClass != null && proxyClass != Object.class && !Proxy.isProxyClass(proxyClass)) {
                    return proxyClass;
                }
            }
        }

        if (method != null) {
            return method.getDeclaringClass();
        }

        return Object.class;
    }

    private static Class<?> userClass(Class<?> clazz) {
        if (clazz == null) {
            return Object.class;
        }

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            String name = current.getName();
            if (name.contains("$$") || name.contains("ByteBuddy")) {
                current = current.getSuperclass();
                continue;
            }
            return current;
        }

        return Object.class;
    }

    /**
     * 异常解包，获取原始业务异常
     */
    private static Throwable unwrap(Throwable e) {
        if ((e instanceof InvocationTargetException || e instanceof UndeclaredThrowableException)
                && e.getCause() != null) {
            return e.getCause();
        }
        return e;
    }

    /**
     * 判断是否为忽略的异常（业务异常降级）
     */
    private static boolean isIgnoredException(Throwable e, LogTraceOptions options) {
        // 处理 Class 数组形式（注解驱动）
        if (options.getIgnoreExceptionClasses() != null) {
            for (Class<? extends Throwable> ignore : options.getIgnoreExceptionClasses()) {
                if (ignore.isAssignableFrom(e.getClass())) {
                    return true;
                }
            }
        }
        // 处理类名列表形式（配置驱动）
        if (options.getIgnoreExceptionNames() != null) {
            String className = e.getClass().getName();
            return options.getIgnoreExceptionNames().contains(className);
        }
        return false;
    }

    /**
     * 日志追踪统一配置选项
     */
    @Data
    @Builder
    public static class LogTraceOptions {
        private Class<?> targetClass;
        private String action;
        private long slowThreshold;
        private Class<? extends Throwable>[] ignoreExceptionClasses;
        private List<String> ignoreExceptionNames;
    }
}