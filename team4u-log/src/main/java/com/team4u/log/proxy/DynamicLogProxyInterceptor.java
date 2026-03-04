package com.team4u.log.proxy;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.log.proxy.ProxyRuleRepository.ProxyRule;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 动态配置驱动的日志代理拦截器 (专为第三方类库设计)
 * <p>
 * 无需在源码中添加注解，通过动态控制拦截行为。
 */
public class DynamicLogProxyInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Class<?> targetClass = LogTraceSupport.getTargetClass(invocation, method);

        String className = targetClass.getName();
        String methodName = method.getName();

        // 1. 从组件自治仓库获取该类的动态代理规则
        ProxyRule rule = ProxyRuleRepository.getInstance().getRule(className);

        // 2. 如果没有配置规则，或者当前方法不在拦截名单中，直接放行，零性能损耗
        if (rule == null || !isMethodMatched(methodName, rule.getMethods())) {
            return invocation.proceed();
        }

        // 3. 命中规则，执行日志追踪与脱敏 (复用 LogTraceSupport 逻辑)
        LogTraceSupport.LogTraceOptions options = LogTraceSupport.LogTraceOptions.builder()
                .targetClass(targetClass)
                .action(methodName)
                .slowThreshold(rule.getSlowThreshold())
                .ignoreExceptionNames(rule.getIgnoreExceptions())
                .build();

        return LogTraceSupport.proceed(invocation, options);
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
}
