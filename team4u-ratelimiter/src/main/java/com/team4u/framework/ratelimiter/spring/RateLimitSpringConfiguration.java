package com.team4u.framework.ratelimiter.spring;

import com.team4u.framework.ratelimiter.proxy.RateLimit;
import com.team4u.framework.ratelimiter.proxy.RateLimitInterceptor;
import com.team4u.framework.ratelimiter.proxy.RateLimitProxyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

import java.lang.reflect.Method;

/**
 * 限流组件 Spring 自动化配置类
 * <p>
 * 注册 {@link RateLimitBeanPostProcessor}：类中含 {@code @RateLimit} 方法的 Bean
 * 用 {@link RateLimitProxyFactory} 包装为限流代理。基础设施类（Advisor、
 * BeanPostProcessor、已被 AOP 增强的代理）跳过；final 类无法代理时记录
 * warn 并跳过（返回原 Bean，不阻断启动）。
 * </p>
 *
 * @author jay.wu
 */
@Configuration
public class RateLimitSpringConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static RateLimitBeanPostProcessor rateLimitBeanPostProcessor() {
        return new RateLimitBeanPostProcessor();
    }

    /**
     * 限流 Bean 后置处理器：按 {@code @RateLimit} 注解存在性决定是否包装代理
     */
    public static class RateLimitBeanPostProcessor implements BeanPostProcessor {

        private static final Logger log = LoggerFactory.getLogger(RateLimitBeanPostProcessor.class);

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean == null || isInfrastructure(bean) || AopUtils.isAopProxy(bean)) {
                return bean;
            }
            if (!hasRateLimitMethod(bean.getClass())) {
                return bean;
            }
            try {
                return RateLimitProxyFactory.proxy(bean);
            } catch (Exception e) {
                // final 类等无法代理的目标：warn 并跳过，保持原 Bean 可用
                log.warn("RateLimit|proxySkipped|bean={}|class={}|reason={}",
                        beanName, bean.getClass().getName(), e.getMessage());
                return bean;
            }
        }

        private boolean isInfrastructure(Object bean) {
            return bean instanceof BeanPostProcessor
                    || bean instanceof Advisor
                    || bean instanceof AopInfrastructureBean;
        }

        /**
         * 类自身或其接口层次的方法上是否标注 {@code @RateLimit}
         */
        private boolean hasRateLimitMethod(Class<?> beanClass) {
            for (Method method : beanClass.getMethods()) {
                if (RateLimitInterceptor.resolveAnnotation(method, beanClass) != null) {
                    return true;
                }
            }
            return false;
        }
    }
}
