package com.team4u.framework.bean;

import com.team4u.framework.bean.core.BeanFactory;
import com.team4u.framework.bean.exception.NoSuchBeanDefinitionException;
import com.team4u.framework.bean.provider.LocalBeanContainer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Bean 门面管理器
 *
 * @author team4u
 */
public class BeanManager {

    private static final BeanManager INSTANCE = new BeanManager();

    private final List<BeanFactory> factories = new CopyOnWriteArrayList<>();
    private final LocalBeanContainer localContainer = new LocalBeanContainer();

    private BeanManager() {
        // 默认加入本地兜底容器
        addProvider(localContainer);
        // 通过 Java SPI 加载扩展容器
        loadSpiProviders();
    }

    public static BeanManager getInstance() {
        return INSTANCE;
    }

    /**
     * 智能获取 Bean
     * <p>
     * 如果容器中不存在，则通过 builder 创建并注册到本地容器
     */
    public <T> T loadBean(Class<T> type, Supplier<T> beanBuilder) {
        T bean = getBean(type);
        if (bean != null) {
            return bean;
        }

        T newBean = beanBuilder.get();
        localContainer.registerBean(newBean);
        return newBean;
    }

    /**
     * 根据类型获取 Bean
     */
    public <T> T getBean(Class<T> type) {
        for (BeanFactory factory : factories) {
            T bean = factory.getBean(type);
            if (bean != null) {
                return bean;
            }
        }
        return null;
    }

    /**
     * 根据名称获取 Bean
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(String name) {
        for (BeanFactory factory : factories) {
            T bean = (T) factory.getBean(name);
            if (bean != null) {
                return bean;
            }
        }
        return null;
    }

    /**
     * 强制获取 Bean，若不存在则抛出异常
     */
    public <T> T getRequiredBean(Class<T> type) {
        T bean = getBean(type);
        if (bean == null) {
            throw new NoSuchBeanDefinitionException("No qualifying bean of type " + type.getName());
        }
        return bean;
    }

    /**
     * 获取指定类型的所有 Bean
     */
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        return factories.stream()
                .flatMap(f -> f.getBeansOfType(type).entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    /**
     * 注册单例 Bean 到本地容器
     */
    public <T> void registerBean(String beanName, T bean) {
        localContainer.registerBean(beanName, bean);
    }

    /**
     * 动态添加 Bean 提供者
     */
    public void addProvider(BeanFactory factory) {
        factories.add(factory);
        sortFactories();
    }

    private void loadSpiProviders() {
        ServiceLoader<BeanFactory> loader = ServiceLoader.load(BeanFactory.class);
        for (BeanFactory factory : loader) {
            addProvider(factory);
        }
    }

    private void sortFactories() {
        factories.sort(Comparator.comparingInt(BeanFactory::getOrder));
    }
}
