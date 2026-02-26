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
 *
 * @author jay.wu
 */
public class LocalBeanContainer implements BeanFactory, BeanRegistry {

    /**
     * 使用 ConcurrentHashMap 保证并发安全
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
        // 利用 putIfAbsent 保证原子性
        Object existing = singletonObjects.putIfAbsent(beanName, bean);
        if (existing == null) {
            EventDispatcher.publish(new BeanInitializedEvent(beanName, bean));
            return true;
        }
        return false;
    }

    @Override
    public <T> boolean registerBean(T bean) {
        return registerBean(bean.getClass().getName(), bean);
    }

    @Override
    public int getOrder() {
        // 最低优先级，作为兜底
        return Integer.MAX_VALUE;
    }
}
