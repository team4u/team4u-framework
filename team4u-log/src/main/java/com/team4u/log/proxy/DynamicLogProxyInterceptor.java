package com.team4u.log.proxy;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.log.Loggers;
import com.team4u.log.config.LogConfigManager;
import com.team4u.log.config.LogDynamicConfig;
import com.team4u.log.mask.FastMasker;
import com.team4u.log.mask.MaskType;
import com.team4u.log.mask.config.MaskRuleRepository;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

/**
 * 动态配置驱动的日志代理拦截器 (专为第三方类库设计)
 * <p>
 * 无需在源码中添加注解，通过 {@link LogDynamicConfig#getProxyRules()} 动态控制拦截行为。
 */
public class DynamicLogProxyInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        // ByteBuddy 生成的类的父类即原始类
        Class<?> targetClass = invocation.getProxy().getClass().getSuperclass();
        if (targetClass == null || targetClass == Object.class) {
            targetClass = method.getDeclaringClass();
        }

        String className = targetClass.getName();
        String methodName = method.getName();

        // 1. 从全局配置中心获取该类的动态代理规则
        LogDynamicConfig config = LogConfigManager.getInstance().getCurrentConfig();
        LogDynamicConfig.ProxyRule rule = null;
        if (config != null && config.getProxyRules() != null) {
            rule = config.getProxyRules().get(className);
        }

        // 2. 如果没有配置规则，或者当前方法不在拦截名单中，直接放行，零性能损耗
        if (rule == null || !isMethodMatched(methodName, rule.getMethods())) {
            return invocation.proceed();
        }

        // 3. 命中规则，执行日志追踪与脱敏 (复用 Loggers 逻辑)
        long start = System.currentTimeMillis();
        Object[] args = invocation.getArguments();

        try {
            Object result = invocation.proceed();
            long cost = System.currentTimeMillis() - start;

            // 对入参进行主动脱敏处理（补齐 Jackson 无法对数组内简单字符串脱敏的短板）
            Object maskedArgs = maskArgs(method, args, targetClass);

            Loggers loggers = Loggers.of(targetClass)
                    .action(methodName)
                    .duration(cost)
                    .kv("req", maskedArgs)
                    .kv("resp", result);

            if (rule.getSlowThreshold() > 0 && cost > rule.getSlowThreshold()) {
                loggers.atWarn().status("slow_success").kv("slowThreshold", rule.getSlowThreshold());
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

            Object maskedArgs = maskArgs(method, args, targetClass);

            Loggers loggers = Loggers.of(targetClass)
                    .action(methodName)
                    .duration(cost)
                    .kv("req", maskedArgs);

            if (isIgnoredException(e, rule.getIgnoreExceptions())) {
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

    /**
     * 对方法入参进行主动脱敏
     */
    private Object maskArgs(Method method, Object[] args, Class<?> targetClass) {
        if (args == null || args.length == 0) {
            return args;
        }

        Parameter[] parameters = method.getParameters();
        Object[] maskedArgs = new Object[args.length];
        String className = targetClass.getName();

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof String && i < parameters.length) {
                String paramName = parameters[i].getName();
                MaskType maskType = MaskRuleRepository.getInstance()
                        .findRule(className, paramName);
                if (maskType != null) {
                    maskedArgs[i] = FastMasker.mask((String) arg, maskType);
                    continue;
                }
            }
            // 其它类型（如 DTO）交给 Jackson 的 DynamicMaskSerializerModifier 后置处理
            maskedArgs[i] = arg;
        }
        return maskedArgs;
    }

    private boolean isMethodMatched(String methodName, List<String> configuredMethods) {
        if (configuredMethods == null || configuredMethods.isEmpty()) {
            return false;
        }
        if (configuredMethods.contains("*")) {
            return true;
        }
        return configuredMethods.contains(methodName);
    }

    private boolean isIgnoredException(Throwable e, List<String> ignoreExceptions) {
        if (ignoreExceptions == null || ignoreExceptions.isEmpty()) {
            return false;
        }
        String exceptionClassName = e.getClass().getName();
        for (String ignore : ignoreExceptions) {
            if (exceptionClassName.equals(ignore)) {
                return true;
            }
        }
        return false;
    }
}
