package com.team4u.framework.bean.core;

/**
 * Bean 注册接口
 * <p>
 * 职责：仅负责注册 Bean（写操作）
 *
 * @author team4u
 */
public interface BeanRegistry {

    /**
     * 注册单例 Bean
     *
     * @param beanName Bean 名称
     * @param bean     Bean 实例
     * @return 是否注册成功
     */
    <T> boolean registerBean(String beanName, T bean);

    /**
     * 注册单例 Bean（使用类全名作为名称）
     *
     * @param bean Bean 实例
     * @return 是否注册成功
     */
    <T> boolean registerBean(T bean);
}
