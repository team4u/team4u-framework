package com.team4u.framework.ratelimiter.spring;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.spring.AnnotationProxyBeanPostProcessor;
import com.team4u.framework.ratelimiter.proxy.RateLimit;
import com.team4u.framework.ratelimiter.proxy.RateLimitInterceptor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * 限流组件 Spring 自动化配置类
 * <p>
 * 注册 {@link RateLimitBeanPostProcessor}：类中含 {@code @RateLimit} 方法的 Bean
 * 被自动包装为限流代理。装配逻辑（注解探测、代理构建、失败兜底）继承自
 * {@link AnnotationProxyBeanPostProcessor} 公共模板：基础设施类与既有 AOP 代理
 * 跳过；final 类无法代理时 warn 并跳过（返回原 Bean，不阻断启动）。
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
     * 限流 Bean 后置处理器：注解类型 + 拦截器工厂即全部装配逻辑
     * <p>
     * 拦截器在调用期按 (method, targetClass) 逐方法解析注解（经公共解析器），
     * Bean 级注解实例仅用于构造拦截器。
     */
    public static class RateLimitBeanPostProcessor extends AnnotationProxyBeanPostProcessor<RateLimit> {

        @Override
        protected Class<RateLimit> getAnnotationType() {
            return RateLimit.class;
        }

        @Override
        protected MethodInterceptor createInterceptor(RateLimit annotation) {
            return new RateLimitInterceptor();
        }
    }
}
