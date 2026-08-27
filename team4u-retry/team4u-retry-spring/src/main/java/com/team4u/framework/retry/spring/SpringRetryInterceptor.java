package com.team4u.framework.retry.spring;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.inline.DefaultInlineRetryClient;
import com.team4u.framework.retry.inline.InlineRetryClient;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;
import com.team4u.framework.retry.common.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.proxy.RetryDelegate;
import com.team4u.framework.retry.proxy.RetryMethodResolver;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Spring AOP 体系下的重试拦截器实现。
 */
public class SpringRetryInterceptor implements MethodInterceptor {

    private final BeanFactory beanFactory;
    private final ListableBeanFactory listableBeanFactory;
    private final RetryExecutorManager retryExecutorManager;
    private volatile RetryDelegate delegate;

    public SpringRetryInterceptor(
            BeanFactory beanFactory,
            ListableBeanFactory listableBeanFactory,
            RetryExecutorManager retryExecutorManager) {
        this.beanFactory = beanFactory;
        this.listableBeanFactory = listableBeanFactory;
        this.retryExecutorManager = retryExecutorManager;
    }

    /**
     * 确保重试委托类已初始化。
     * <p>
     * 采用双重检查锁定模式（DCL）实现延迟加载，自动从 Spring 容器获取重试客户端。
     */
    private void ensureDelegateInitialized() {
        if (delegate == null) {
            synchronized (this) {
                if (delegate == null) {
                    InlineRetryClient inlineClient = getBean(InlineRetryClient.class,
                            DefaultInlineRetryClient.getInstance());
                    ManagedRetryClient managedClient = getBean(ManagedRetryClient.class, null);
                    delegate = new RetryDelegate(inlineClient, managedClient);
                    delegate.setScheduler(retryExecutorManager.getScheduler());
                }
            }
        }
    }

    /**
     * 从 BeanFactory 获取指定类型的 Bean。
     * <p>
     * 如果当前的 {@code beanFactory} 中不存在，则尝试通过 {@link BeanManager} 静态查找。
     *
     * @param clazz        Bean 类型
     * @param defaultValue 默认值
     * @param <T>          泛型类型
     * @return 找到的 Bean 实例或默认值
     */
    private <T> T getBean(Class<T> clazz, T defaultValue) {
        try {
            return beanFactory.getBean(clazz);
        } catch (BeansException e) {
            try {
                return BeanManager.getInstance().getBean(clazz);
            } catch (Exception ex) {
                return defaultValue;
            }
        }
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        ensureDelegateInitialized();

        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Class<?> targetClass = null;

        if (target != null) {
            targetClass = AopUtils.getTargetClass(target);
        }
        RetryMethodResolver.ResolvedRetryMethod resolved = RetryMethodResolver.resolve(method, targetClass);

        return delegate.executeWithRetry(
                method,
                resolved.getEffectiveMethod(),
                resolved.getRecoveryTargetType(),
                resolveRecoveryTargetBeanName(invocation.getThis(), resolved.getRecoveryTargetType()),
                invocation.getArguments(),
                resolved.getRetryable(),
                () -> {
                    try {
                        return invocation.proceed();
                    } catch (Exception | Error e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
    }

    private String resolveRecoveryTargetBeanName(Object proxy, Class<?> recoveryTargetType) {
        if (proxy == null) {
            return null;
        }
        for (String beanName : listableBeanFactory.getBeanDefinitionNames()) {
            Object candidate;
            try {
                candidate = listableBeanFactory.getBean(beanName);
            } catch (BeansException ex) {
                continue;
            }
            if (candidate == proxy) {
                return beanName;
            }
        }
        if (recoveryTargetType == null) {
            return null;
        }
        Map<String, ?> candidates = listableBeanFactory.getBeansOfType(recoveryTargetType);
        if (candidates.size() == 1) {
            return candidates.keySet().iterator().next();
        }
        return null;
    }
}
