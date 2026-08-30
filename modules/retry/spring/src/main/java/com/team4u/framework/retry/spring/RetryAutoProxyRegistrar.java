package com.team4u.framework.retry.spring;

import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * 通过 Spring AOP 的升级机制注册自动代理创建器，避免与宿主应用的 AOP 基础设施冲突。
 */
public class RetryAutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
    }
}
