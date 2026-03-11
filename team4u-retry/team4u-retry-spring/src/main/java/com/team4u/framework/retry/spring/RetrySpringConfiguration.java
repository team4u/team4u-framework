package com.team4u.framework.retry.spring;

import com.team4u.framework.bean.provider.SpringBeanContainer;
import com.team4u.framework.retry.client.DefaultInlineRetryClient;
import com.team4u.framework.retry.client.InlineRetryClient;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.proxy.Retryable;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * 重试组件 Spring 自动化配置类
 * <p>
 * 负责注册 AOP 切面、拦截器以及相关的基础设施 Bean，以支持在 Spring 环境下使用重试功能。
 */
@Configuration
public class RetrySpringConfiguration {

    /**
     * 定义进程内重试客户端 Bean。
     *
     * @return 默认的单例进程内重试客户端
     */
    @Bean
    public InlineRetryClient inlineRetryClient() {
        return DefaultInlineRetryClient.getInstance();
    }

    /**
     * 定义重试通知器 Bean。
     * <p>
     * 用于拦截标记了 {@link Retryable} 注解的方法，自动注入切面拦截逻辑。
     *
     * @param beanFactory Spring 容器上下文
     * @return 重试通知器
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public RetryAdvisor retryAdvisor(BeanFactory beanFactory,
                                     ListableBeanFactory listableBeanFactory,
                                     RetryExecutorManager retryExecutorManager) {
        RetryAdvisor advisor = new RetryAdvisor();
        advisor.setAdvice(new SpringRetryInterceptor(beanFactory, listableBeanFactory, retryExecutorManager));
        advisor.setOrder(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1);
        return advisor;
    }

    @Bean(destroyMethod = "shutdown")
    public RetryExecutorManager retryExecutorManager() {
        return new RetryExecutorManager(false);
    }

    @Bean
    public SpringBeanContainer springBeanContainer() {
        return new SpringBeanContainer();
    }

    /**
     * 定义恢复处理器扫描器 Bean。
     *
     * @return 恢复处理器扫描注册实现
     */
    @Bean
    public DefaultRecoveryHandlerRegistrar defaultRecoveryHandlerRegistrar() {
        return new DefaultRecoveryHandlerRegistrar();
    }

    public static class RetryAdvisor extends AbstractBeanFactoryPointcutAdvisor {
        @Override
        public Pointcut getPointcut() {
            Pointcut classLevelPointcut = new AnnotationMatchingPointcut(Retryable.class, true);
            Pointcut methodLevelPointcut = new AnnotationMatchingPointcut(null, Retryable.class, true);
            return new ComposablePointcut(classLevelPointcut).union(methodLevelPointcut);
        }
    }

    public static class DefaultRecoveryHandlerRegistrar {
        public DefaultRecoveryHandlerRegistrar() {
            RecoveryHandlerRegistry.global().autoScan();
        }
    }
}
