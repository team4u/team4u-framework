package com.team4u.framework.singleflight.spring;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.spring.AnnotationProxyBeanPostProcessor;
import com.team4u.framework.singleflight.proxy.SingleFlight;
import com.team4u.framework.singleflight.proxy.SingleFlightInterceptor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * Spring 集成配置：注册包装含 {@code @SingleFlight} 方法 bean 的后置处理器。
 * <p>
 * 装配逻辑（注解探测、代理构建、边界防御）继承自
 * {@link AnnotationProxyBeanPostProcessor} 公共模板；与限流等模块不同，
 * 本模块在代理构建失败时快速失败（覆盖 {@code onProxyFailure}），
 * 与既有行为保持一致。
 * </p>
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
     * Bean 后置处理器：注解类型 + 拦截器工厂即全部装配逻辑。
     * <p>
     * 拦截器在调用期按 (method, targetClass) 逐方法解析注解（经公共解析器），
     * Bean 级注解实例仅用于构造拦截器。
     */
    public static class SingleFlightBeanPostProcessor extends AnnotationProxyBeanPostProcessor<SingleFlight> {

        @Override
        protected Class<SingleFlight> getAnnotationType() {
            return SingleFlight.class;
        }

        @Override
        protected MethodInterceptor createInterceptor(SingleFlight annotation) {
            return new SingleFlightInterceptor();
        }

        /**
         * 代理构建失败快速失败：回源合并的协调语义（同 key 单执行）依赖代理生效，
         * 静默退化为直通会让并发回源意外放大，宁可启动期暴露。
         */
        @Override
        protected Object onProxyFailure(Object bean, String beanName, Exception e) {
            throw new IllegalStateException("Failed to create singleflight proxy|bean=" + beanName
                    + "|class=" + bean.getClass().getName(), e);
        }
    }
}
