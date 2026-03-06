package com.team4u.framework.retry.spring;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.proxy.Retryable;
import org.springframework.aop.Pointcut;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * Spring 配置类，用于无缝接入重试能力。
 */
@Configuration
public class RetrySpringConfiguration {

    @Bean(name = AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static DefaultAdvisorAutoProxyCreator retryAutoProxyCreator() {
        return new DefaultAdvisorAutoProxyCreator();
    }

    @Bean
    public RetryAdvisor retryAdvisor(BeanFactory beanFactory) {
        RetryAdvisor advisor = new RetryAdvisor();
        advisor.setAdvice(new SpringRetryInterceptor(() -> getRetryBackend(beanFactory)));
        advisor.setOrder(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1);
        return advisor;
    }

    private RetryBackend getRetryBackend(BeanFactory beanFactory) {
        try {
            return beanFactory.getBean(RetryBackend.class);
        } catch (BeansException e) {
            return BeanManager.getInstance().getBean(RetryBackend.class);
        }
    }

    /**
     * 自定义 Advisor，使用 Spring AOP 切点匹配类级与方法级注解。
     */
    public static class RetryAdvisor extends AbstractBeanFactoryPointcutAdvisor {
        @Override
        public Pointcut getPointcut() {
            Pointcut classLevelPointcut = new AnnotationMatchingPointcut(Retryable.class, true);
            Pointcut methodLevelPointcut = new AnnotationMatchingPointcut(null, Retryable.class, true);
            return new ComposablePointcut(classLevelPointcut).union(methodLevelPointcut);
        }
    }
}
