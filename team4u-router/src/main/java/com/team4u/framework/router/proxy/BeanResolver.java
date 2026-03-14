package com.team4u.framework.router.proxy;

/**
 * Bean 定位解析器 (Bean Resolver)
 * <p>
 * 该接口定义了如何根据路由命中的名称字符串从外部容器（如 Spring ApplicationContext 或自定义 BeanPool）
 * 中提取真实的物理对象。它是框架与宿主运行时环境的粘合层。
 * </p>
 */
public interface BeanResolver {
    Object getBean(String beanName);
}
