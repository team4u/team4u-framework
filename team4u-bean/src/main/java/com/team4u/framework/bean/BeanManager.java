package com.team4u.framework.bean;

import com.team4u.framework.base.util.ServiceLoaderUtil;
import com.team4u.framework.bean.core.BeanFactory;
import com.team4u.framework.bean.exception.NoSuchBeanDefinitionException;
import com.team4u.framework.bean.provider.LocalBeanContainer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Bean 全局门面管理器
 * <p>
 * 提供对系统中所有 {@link BeanFactory} 的统一访问入口。支持 SPI 自动发现扩展容器，
 * 默认包含一个 {@link LocalBeanContainer} 作为本地 Bean 注册与查询的兜底。
 * 建议在编写通用 SDK 或底层组件时，通过此类获取依赖，以实现对具体容器实现（如 Spring）的解耦。
 *
 * @author jay.wu
 */
public class BeanManager {

    private static final BeanManager INSTANCE = new BeanManager();

    /**
     * 容器提供者列表，按优先级（Order）排序
     */
    private final List<BeanFactory> factories = new CopyOnWriteArrayList<>();

    /**
     * 默认的本地容器，用于动态注册 Bean
     */
    private final LocalBeanContainer localContainer = new LocalBeanContainer();

    private BeanManager() {
        // 初始加入本地兜底容器，确保基础功能可用
        addProvider(localContainer);
        // 基于 Java SPI 机制加载并集成外部自定义容器实现
        loadSpiProviders();
    }

    /**
     * 获取全局唯一实例
     */
    public static BeanManager getInstance() {
        return INSTANCE;
    }

    /**
     * 智能获取 Bean：若容器中不存在，则通过提供的 Supplier 创建、初始化并注册到本地容器中。
     * <p>
     * 该方法常用于实现组件的延迟初始化（Lazy Loading）。
     *
     * @param type        Bean 类型
     * @param beanBuilder Bean 创建逻辑
     * @param <T>         Bean 类型泛型
     * @return 已存在的或新创建的 Bean 实例
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
     * 根据类型查找 Bean。
     * <p>
     * 将按优先级顺序遍历所有注册的 {@link BeanFactory}，返回第一个匹配的实例。
     *
     * @param type Bean 类型
     * @param <T>  Bean 类型泛型
     * @return 匹配的 Bean 实例，若未找到则返回 null
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
     * 根据名称查找 Bean。
     * <p>
     * 将按优先级顺序遍历所有注册的 {@link BeanFactory}，返回第一个匹配的实例。
     *
     * @param name Bean 名称
     * @param <T>  Bean 类型泛型
     * @return 匹配的 Bean 实例，若未找到则返回 null
     */
    public <T> T getBean(String name) {
        for (BeanFactory factory : factories) {
            T bean = factory.getBean(name);
            if (bean != null) {
                return bean;
            }
        }
        return null;
    }

    /**
     * 强制根据类型查找 Bean。
     * <p>
     * 若未找到匹配的 Bean，将抛出 {@link NoSuchBeanDefinitionException}。
     *
     * @param type Bean 类型
     * @param <T>  Bean 类型泛型
     * @return 匹配的 Bean 实例
     * @throws NoSuchBeanDefinitionException 当 Bean 不存在时抛出
     */
    public <T> T getRequiredBean(Class<T> type) {
        T bean = getBean(type);
        if (bean == null) {
            throw new NoSuchBeanDefinitionException("No qualifying bean of type " + type.getName());
        }
        return bean;
    }

    /**
     * 获取指定类型的所有 Bean。
     * <p>
     * 将聚合所有 {@link BeanFactory} 中的结果。若名称冲突，高优先级容器中的 Bean 将覆盖低优先级容器。
     *
     * @param type Bean 类型
     * @param <T>  Bean 类型泛型
     * @return 包含所有匹配 Bean 的 Map（名称 -> 实例）
     */
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        return factories.stream()
                .flatMap(f -> f.getBeansOfType(type).entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    /**
     * 手动向本地容器注册单例 Bean。
     *
     * @param beanName Bean 名称
     * @param bean     Bean 实例
     */
    public <T> void registerBean(String beanName, T bean) {
        localContainer.registerBean(beanName, bean);
    }

    /**
     * 动态添加自定义的 Bean 工厂/提供者。
     * <p>
     * 添加后将自动根据其定义的 order 重新排序。
     *
     * @param factory 自定义工厂实现
     */
    public void addProvider(BeanFactory factory) {
        factories.add(factory);
        sortFactories();
    }

    /**
     * 通过 Java 标准 SPI 机制加载第三方扩展的 Bean 提供者
     * <p>
     * 容错加载：单个实现初始化失败只记录告警并跳过，不影响其余提供者与全局初始化。
     * </p>
     */
    private void loadSpiProviders() {
        for (BeanFactory factory : ServiceLoaderUtil.loadAvailableList(BeanFactory.class)) {
            addProvider(factory);
        }
    }

    /**
     * 根据优先级对所有工厂进行排序
     */
    private void sortFactories() {
        factories.sort(Comparator.comparingInt(BeanFactory::getOrder));
    }
}
