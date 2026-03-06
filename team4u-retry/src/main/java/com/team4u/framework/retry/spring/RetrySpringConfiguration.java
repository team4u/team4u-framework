package com.team4u.framework.retry.spring;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.proxy.Retryable;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
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
 * 重试组件 Spring 自动化配置
 * <p>
 * 提供 AOP 切面及拦截器，支持通过 @Retryable 注解自动接入重试逻辑。
 */
@Configuration
public class RetrySpringConfiguration {

    /**
     * 定义默认的 Advisor 自动代理创建器
     */
    @Bean(name = AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static DefaultAdvisorAutoProxyCreator retryAutoProxyCreator() {
        return new DefaultAdvisorAutoProxyCreator();
    }

    /**
     * 定义重试切面 Advisor，绑定 SpringRetryInterceptor 拦截器
     */
    @Bean
    public RetryAdvisor retryAdvisor(BeanFactory beanFactory) {
        RetryAdvisor advisor = new RetryAdvisor();
        advisor.setAdvice(new SpringRetryInterceptor(() -> getRetryBackend(beanFactory)));
        // 设定较低优先级，确保重试拦截器在外层执行
        advisor.setOrder(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1);
        return advisor;
    }

    /**
     * 注册默认恢复处理器
     */
    @Bean
    public DefaultRecoveryHandlerRegistrar defaultRecoveryHandlerRegistrar() {
        return new DefaultRecoveryHandlerRegistrar();
    }

    /**
     * 解析重试后端实现，优先从 Spring 容器获取
     */
    private RetryBackend getRetryBackend(BeanFactory beanFactory) {
        try {
            return beanFactory.getBean(RetryBackend.class);
        } catch (BeansException e) {
            return BeanManager.getInstance().getBean(RetryBackend.class);
        }
    }

    /**
     * 自定义重试 Advisor
     * <p>
     * 使用 Spring AOP 组合切点，匹配类级或方法级上的 @Retryable 注解。
     */
    public static class RetryAdvisor extends AbstractBeanFactoryPointcutAdvisor {
        @Override
        public Pointcut getPointcut() {
            // 类级别匹配
            Pointcut classLevelPointcut = new AnnotationMatchingPointcut(Retryable.class, true);
            // 方法级别匹配
            Pointcut methodLevelPointcut = new AnnotationMatchingPointcut(null, Retryable.class, true);
            return new ComposablePointcut(classLevelPointcut).union(methodLevelPointcut);
        }
    }

    /**
     * 自动注册默认恢复处理器
     */
    public static class DefaultRecoveryHandlerRegistrar {
        public DefaultRecoveryHandlerRegistrar() {
            RecoveryHandlerRegistry.ensureDefaultProxyRecoveryHandlerRegistered();
        }
    }
}
