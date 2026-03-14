package com.team4u.framework.base.util;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Spring 工具类
 * <p>
 * 提供从静态上下文获取 Spring 管理 Bean 的能力。
 * 该工具主要用于基础设施层的兜底场景，不建议业务代码将其作为常规依赖获取方式。
 * 使用该类前，请确保此类已在 Spring 容器中注册（如通过 {@code @Component} 扫描）。
 * </p>
 *
 * @author jay.wu
 */
@Component
public class SpringUtil implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    /**
     * 获取指定类型的 Bean
     *
     * @param clazz Bean 的类型
     * @param <T>   Bean 泛型
     * @return 匹配类型的 Bean 实例
     * @throws IllegalStateException 当 ApplicationContext 尚未初始化时抛出
     */
    public static <T> T getBean(Class<T> clazz) {
        ensureApplicationContext();
        return applicationContext.getBean(clazz);
    }

    /**
     * 获取指定名称的 Bean
     *
     * @param name Bean 的名称
     * @return 匹配名称的 Bean 实例
     * @throws IllegalStateException 当 ApplicationContext 尚未初始化时抛出
     */
    public static Object getBean(String name) {
        ensureApplicationContext();
        return applicationContext.getBean(name);
    }

    private static void ensureApplicationContext() {
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext 尚未初始化");
        }
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        SpringUtil.applicationContext = applicationContext;
    }
}
