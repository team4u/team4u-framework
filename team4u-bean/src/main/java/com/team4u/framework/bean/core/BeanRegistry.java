package com.team4u.framework.bean.core;

/**
 * Bean 注册服务接口
 * <p>
 * 该接口定义了将单例 Bean 实例显式注入或注册到容器中的标准契约。
 * 遵循职责分离原则，本接口专门负责“写”操作，是对 {@link BeanFactory} 只读能力的补充。
 *
 * @author jay.wu
 */
public interface BeanRegistry {

    /**
     * 以指定名称向容器注册一个单例 Bean 实例。
     *
     * @param beanName 全局唯一的 Bean 名称
     * @param bean     Bean 实例
     * @param <T>      Bean 类型
     * @return 若注册成功（例如该名称尚不存在）则返回 true，否则返回 false
     */
    <T> boolean registerBean(String beanName, T bean);

    /**
     * 向容器注册一个单例 Bean 实例。
     * <p>
     * 默认情况下，通常采用 {@code bean.getClass().getName()} 作为其注册名称。
     *
     * @param bean Bean 实例
     * @param <T>  Bean 类型
     * @return 若注册成功则返回 true，否则返回 false
     */
    <T> boolean registerBean(T bean);
}
