package com.team4u.framework.bean.core;

import java.util.Map;

/**
 * Bean 工厂接口
 * <p>
 * 职责：仅负责获取 Bean（读操作）
 *
 * @author jay.wu
 */
public interface BeanFactory {

    /**
     * 根据名称获取 Bean
     */
    <T> T getBean(String name);

    /**
     * 根据类型获取 Bean
     */
    <T> T getBean(Class<T> type);

    /**
     * 获取指定类型的所有 Bean 映射
     */
    <T> Map<String, T> getBeansOfType(Class<T> type);

    /**
     * 获取容器优先级
     * <p>
     * 数值越小优先级越高
     */
    int getOrder();
}
