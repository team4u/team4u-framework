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
 * Spring 集成配置：注册包装含 {@code @SingleFlight} 方法 bean 的后置处理器。
 *
 * @author jay.wu
 */
@Configuration
public class SingleFlightSpringConfiguration {

    /**
     * 以基础设施角色注册后置处理器，避免被业务组件扫描逻辑误处理。
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static SingleFlightBeanPostProcessor singleFlightBeanPostProcessor() {
        return new SingleFlightBeanPostProcessor();
    }

    /**
     * Bean 后置处理器：初始化完成后，为含 {@code @SingleFlight} 方法的 bean
     * 创建回源合并代理（经 {@code SingleFlightProxyFactory}）。
     */
    public static class SingleFlightBeanPostProcessor implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            // 基础设施 bean 与已是 AOP 代理的 bean 不重复包装，
            // 避免与既有代理链冲突或把切面基础设施包进业务代理
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

        /**
         * 是否为 Spring AOP 基础设施 bean（后置处理器 / 切面 / 基础设施标记）。
         */
        private boolean isInfrastructure(Object bean) {
            return bean instanceof BeanPostProcessor
                    || bean instanceof Advisor
                    || bean instanceof AopInfrastructureBean;
        }

        /**
         * 类的公有方法（含继承）上是否存在可解析的 {@code @SingleFlight} 注解。
         */
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
