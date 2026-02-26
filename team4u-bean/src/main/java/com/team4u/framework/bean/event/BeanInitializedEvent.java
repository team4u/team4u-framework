package com.team4u.framework.bean.event;

import lombok.Getter;

/**
 * Bean 初始化完成事件
 *
 * @author team4u
 */
@Getter
public class BeanInitializedEvent {

    private final String beanName;
    private final Object bean;

    public BeanInitializedEvent(String beanName, Object bean) {
        this.beanName = beanName;
        this.bean = bean;
    }
}
