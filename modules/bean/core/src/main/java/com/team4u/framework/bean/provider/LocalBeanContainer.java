package com.team4u.framework.bean.provider;

import com.team4u.framework.bean.core.BeanFactory;
import com.team4u.framework.bean.core.BeanRegistry;
import com.team4u.framework.bean.event.BeanInitializedEvent;
import com.team4u.framework.bean.event.EventDispatcher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 本地线程安全的 Bean 容器
 * <p>
 * 该容器作为 {@link BeanManager} 的默认实现，基于 {@link ConcurrentHashMap} 提供高性能的单例 Bean 存储与检索。
 * 它是所有容器链中的兜底提供者（Order 为 {@link Integer#MAX_VALUE}）。
 *
 * @author jay.wu
 */
public class LocalBeanContainer implements BeanFactory, BeanRegistry {

    /**
     * 单例对象高速缓存池，Key 为 Bean 名称，Value 为 Bean 实例
     */
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getBean(String name) {
        return (T) singletonObjects.get(name);
    }

    @Override
    public <T> T getBean(Class<T> type) {
        return singletonObjects.values().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
    }

    @Override
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        return singletonObjects.entrySet().stream()
                .filter(e -> type.isInstance(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> type.cast(e.getValue())));
    }

    @Override
    public <T> boolean registerBean(String beanName, T bean) {
        // 利用 putIfAbsent 确保在多线程环境下同一名称的 Bean 仅被注册一次
        Object existing = singletonObjects.putIfAbsent(beanName, bean);
        if (existing == null) {
            // 注册成功后发布初始化完成事件
            EventDispatcher.publish(new BeanInitializedEvent(beanName, bean));
            return true;
        }
        return false;
    }

    @Override
    public <T> boolean registerBean(T bean) {
        // 默认使用实现类的全限定名作为 Bean 名称
        return registerBean(bean.getClass().getName(), bean);
    }

    @Override
    public int getOrder() {
        // 作为最低优先级的兜底容器
        return Integer.MAX_VALUE;
    }
}
