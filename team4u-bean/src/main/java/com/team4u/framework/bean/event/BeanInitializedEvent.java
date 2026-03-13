package com.team4u.framework.bean.event;

import lombok.Getter;

/**
 * Bean 初始化就绪事件
 * <p>
 * 当一个新的单例 Bean 被成功注册并就绪时，容器会发布此事件。
 * 订阅者可以监听该事件以执行额外的初始化逻辑或依赖刷新。
 *
 * @author jay.wu
 */
@Getter
public class BeanInitializedEvent {

    /**
     * 已初始化的 Bean 唯一名称
     */
    private final String beanName;

    /**
     * 已初始化的 Bean 实例对象
     */
    private final Object bean;

    public BeanInitializedEvent(String beanName, Object bean) {
        this.beanName = beanName;
        this.bean = bean;
    }
}
