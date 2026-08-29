package com.team4u.framework.singleflight.spring;

import com.team4u.framework.singleflight.proxy.SingleFlight;
import com.team4u.framework.singleflight.proxy.SingleFlightInterceptor;
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
 * Registers the bean post-processor that wraps beans containing
 * {@code @SingleFlight} methods.
 *
 * @author jay.wu
 */
@Configuration
public class SingleFlightSpringConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static SingleFlightBeanPostProcessor singleFlightBeanPostProcessor() {
        return new SingleFlightBeanPostProcessor();
    }

    public static class SingleFlightBeanPostProcessor implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean == null || isInfrastructure(bean) || AopUtils.isAopProxy(bean)) {
                return bean;
            }
            if (!hasSingleFlightMethod(bean.getClass())) {
                return bean;
            }
            try {
                return com.team4u.framework.singleflight.proxy.SingleFlightProxyFactory.proxy(bean);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create singleflight proxy|bean=" + beanName
                        + "|class=" + bean.getClass().getName(), e);
            }
        }

        private boolean isInfrastructure(Object bean) {
            return bean instanceof BeanPostProcessor
                    || bean instanceof Advisor
                    || bean instanceof AopInfrastructureBean;
        }

        private boolean hasSingleFlightMethod(Class<?> beanClass) {
            for (Method method : beanClass.getMethods()) {
                if (SingleFlightInterceptor.resolveAnnotation(method) != null) {
                    return true;
                }
            }
            return false;
        }
    }
}
