package com.team4u.framework.retry.spring;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.proxy.RetryInterceptor;
import com.team4u.framework.retry.proxy.Retryable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 重试注解处理器。
 * <p>
 * 在 Bean 初始化完成后，扫描其是否持有 {@link Retryable} 注解，
 * 若匹配则通过 team4u-proxy 生成代理对象。
 *
 * @author jay.wu
 */
public class RetryAnnotationBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware, PriorityOrdered {

    private ListableBeanFactory beanFactory;
    private RetryBackend retryBackend;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = ClassUtils.getUserClass(bean.getClass());

        if (isRetryable(targetClass)) {
            Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(targetClass);

            ProxyBuilder<Object> builder;
            if (interfaces.length > 0) {
                @SuppressWarnings("unchecked")
                Class<Object> firstInterface = (Class<Object>) interfaces[0];
                builder = ProxyBuilder.forClass(firstInterface)
                        .withInterfaces(interfaces);
            } else {
                builder = ProxyBuilder.forObject(bean);
            }

            return builder.delegate(bean)
                    .intercept(new RetryInterceptor(getRetryBackend()))
                    .build();
        }

        return bean;
    }

    /**
     * 判断类或其方法是否持有 @Retryable 注解
     */
    private boolean isRetryable(Class<?> targetClass) {
        if (AnnotationUtils.findAnnotation(targetClass, Retryable.class) != null) {
            return true;
        }

        AtomicBoolean result = new AtomicBoolean(false);
        ReflectionUtils.doWithMethods(targetClass, method -> {
            if (AnnotationUtils.findAnnotation(method, Retryable.class) != null) {
                result.set(true);
            }
        });
        return result.get();
    }

    /**
     * 获取重试后端实现
     * 1. 优先从 Spring 容器获取
     * 2. 其次通过 BeanManager 桥接获取（支持非 Spring 环境注册的后端）
     */
    private synchronized RetryBackend getRetryBackend() {
        if (retryBackend != null) {
            return retryBackend;
        }

        try {
            retryBackend = beanFactory.getBean(RetryBackend.class);
        } catch (Exception e) {
            // 尝试从 BeanManager 获取
            retryBackend = BeanManager.getInstance().getBean(RetryBackend.class);
        }

        if (retryBackend == null) {
            throw new IllegalStateException("未找到 RetryBackend 实现，请确保已注册相关 Bean。");
        }
        return retryBackend;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof ListableBeanFactory) {
            this.beanFactory = (ListableBeanFactory) beanFactory;
        }
    }

    @Override
    public int getOrder() {
        // 赋予较低优先级，确保在其他 AOP 处理器之后执行，或者作为最外层代理
        return LOWEST_PRECEDENCE;
    }
}
