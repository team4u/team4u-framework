package com.team4u.framework.message.spring;

import com.team4u.framework.message.core.MessageDispatcher;
import com.team4u.framework.message.core.MessageHandler;
import com.team4u.framework.message.core.interceptor.MessageInterceptor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;

/**
 * 消息框架 Spring 自动装配处理器
 * <p>
 * 实现对 Spring 容器内符合条件的 Bean 的全自动探测与注册。
 * 通过后置处理器机制，动态发现实现 MessageHandler 或 MessageInterceptor 的 Bean 实例，
 * 并将其挂载至全局消息调度分发器。
 *
 * @author jay.wu
 */
@Configuration
public class MessagingAutoConfiguration implements BeanPostProcessor {

    private final MessageDispatcher globalDispatcher;

    @Autowired
    public MessagingAutoConfiguration(MessageDispatcher globalDispatcher) {
        this.globalDispatcher = globalDispatcher;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 自动发现并注册所有的业务处理器实例
        if (bean instanceof MessageHandler) {
            globalDispatcher.addHandler((MessageHandler) bean);
        }
        // 自动发现并注册所有的全局拦截器实例
        if (bean instanceof MessageInterceptor) {
            globalDispatcher.addInterceptor((MessageInterceptor) bean);
        }
        return bean;
    }
}