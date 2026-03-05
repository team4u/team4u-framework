package com.team4u.framework.retry.spring;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.proxy.Retryable;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractBeanFactoryPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 配置类，用于无缝接入重试功能
 */
@Configuration
public class RetrySpringConfiguration {

    @Bean
    public DefaultAdvisorAutoProxyCreator defaultAdvisorAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
        creator.setProxyTargetClass(true);
        return creator;
    }

    @Bean
    public RetryAdvisor retryAdvisor(BeanFactory beanFactory) {
        RetryAdvisor advisor = new RetryAdvisor();
        // 设置拦截器
        advisor.setAdvice(new SpringRetryInterceptor(() -> getRetryBackend(beanFactory)));
        // 可以设置优先级，例如让重试在事务（@Transactional）之外执行
        advisor.setOrder(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1);
        return advisor;
    }

    /**
     * 自定义 Advisor，使用 Spring 的切点匹配
     */
    public static class RetryAdvisor extends AbstractBeanFactoryPointcutAdvisor {
        @Override
        public Pointcut getPointcut() {
            // 匹配类上或方法上的 @Retryable 注解
            return new AnnotationMatchingPointcut(null, Retryable.class, true);
        }
    }

    private RetryBackend getRetryBackend(BeanFactory beanFactory) {
        try {
            return beanFactory.getBean(RetryBackend.class);
        } catch (BeansException e) {
            return BeanManager.getInstance().getBean(RetryBackend.class);
        }
    }
}
