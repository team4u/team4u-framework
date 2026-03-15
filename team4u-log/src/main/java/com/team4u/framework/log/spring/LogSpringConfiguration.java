package com.team4u.framework.log.spring;

import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;

/**
 * team4u-log Spring 配置入口
 */
@Configuration
public class LogSpringConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static DefaultAdvisorAutoProxyCreator team4uLogAutoProxyCreator() {
        return new DefaultAdvisorAutoProxyCreator();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public SpringLogTraceInterceptor springLogTraceInterceptor() {
        return new SpringLogTraceInterceptor();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public AutoLogTraceAdvisor autoLogTraceAdvisor(SpringLogTraceInterceptor interceptor) {
        AutoLogTraceAdvisor advisor = new AutoLogTraceAdvisor(interceptor);
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
        return advisor;
    }
}
