package com.team4u.framework.base.util;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Spring 工具类
 * <p>
 * 提供从静态上下文获取 Spring 管理的 Bean 的能力。
 * 使用该类前，请确保此类已在 Spring 容器中注册（如通过 {@code @Component} 扫描）。
 * </p>
 *
 * @author jay.wu
 */
@Component
public class SpringUtil implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        SpringUtil.applicationContext = applicationContext;
    }

    /**
     * 获取指定类型的 Bean
     *
     * @param clazz Bean 的类型
     * @param <T>   Bean 泛型
     * @return 匹配类型的 Bean 实例
     */
    public static <T> T getBean(Class<T> clazz) {
        return applicationContext.getBean(clazz);
    }

    /**
     * 获取指定名称的 Bean
     *
     * @param name Bean 的名称
     * @return 匹配名称的 Bean 实例
     */
    public static Object getBean(String name) {
        return applicationContext.getBean(name);
    }
}
