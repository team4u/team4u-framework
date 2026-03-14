package com.team4u.framework.router.proxy;

/**
 * Bean 查找抽象，便于多容器和测试隔离。
 */
public interface BeanResolver {
    Object getBean(String beanName);
}
