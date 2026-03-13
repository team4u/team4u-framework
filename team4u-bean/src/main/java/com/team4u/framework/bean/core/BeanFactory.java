package com.team4u.framework.bean.core;

import java.util.Map;

/**
 * Bean 访问工厂接口
 * <p>
 * 该接口定义了从容器中检索和获取 Bean 实例的标准方法。遵循读写分离原则，
 * 本接口仅聚焦于“读”操作，具体的“写”操作由 {@link BeanRegistry} 定义。
 *
 * @author jay.wu
 */
public interface BeanFactory {

    /**
     * 根据 Bean 的唯一名称获取实例。
     *
     * @param name Bean 名称
     * @param <T>  预期的 Bean 类型
     * @return Bean 实例，若不存在则返回 null
     */
    <T> T getBean(String name);

    /**
     * 根据 Bean 的 Class 类型获取实例。
     *
     * @param type Bean 类型
     * @param <T>  Bean 类型泛型
     * @return 匹配的第一个 Bean 实例，若不存在则返回 null
     */
    <T> T getBean(Class<T> type);

    /**
     * 获取指定类型的所有 Bean 实例映射。
     *
     * @param type Bean 类型
     * @param <T>  Bean 类型泛型
     * @return 一个包含名称到实例映射的 Map，绝不返回 null
     */
    <T> Map<String, T> getBeansOfType(Class<T> type);

    /**
     * 获取当前工厂在 {@link BeanManager} 容器链中的优先级顺序。
     * <p>
     * 数值越小，优先级越高，在查找过程中会被更早访问。
     *
     * @return 优先级权重值
     */
    int getOrder();
}
