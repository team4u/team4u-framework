package com.team4u.framework.flow.bean;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.bean.exception.NoSuchBeanDefinitionException;

import java.util.Objects;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.spi.OperationResolver;

/**
 * 基于 {@link BeanManager} 的组件依赖查找解析器（Bean-Backed Operation Resolver）。
 *
 * <p>实现 {@link OperationResolver} 接口，支持根据契约类型（Class）和限定符（Qualifier/BeanName）从 Spring/Team4u 容器中解析单例 Bean 实例。</p>
 *
 * @author jay.wu
 */
public final class BeanOperationResolver implements OperationResolver {
    private final BeanManager beanManager;

    /**
     * 使用全局默认的 {@link BeanManager#getInstance()} 构造解析器。
     */
    public BeanOperationResolver() {
        this(BeanManager.getInstance());
    }

    /**
     * 使用指定的 {@link BeanManager} 容器构造解析器。
     *
     * @param beanManager Bean 管理器，不能为 null
     * @throws NullPointerException 当 beanManager 为 null 时抛出
     */
    public BeanOperationResolver(BeanManager beanManager) {
        this.beanManager = Objects.requireNonNull(beanManager, "beanManager must not be null");
    }

    /**
     * 获取基于全局 {@link BeanManager} 的解析器实例。
     *
     * @return 解析器实例
     */
    public static BeanOperationResolver global() {
        return new BeanOperationResolver(BeanManager.getInstance());
    }

    @Override
    public Object resolve(Class<?> contract, String qualifier) {
        Objects.requireNonNull(contract, "contract must not be null");
        if (qualifier == null) {
            return beanManager.getRequiredBean(contract);
        }

        Object bean = beanManager.getBean(qualifier);
        if (bean == null) {
            throw new NoSuchBeanDefinitionException("No bean named '" + qualifier
                    + "' for contract " + contract.getName());
        }
        if (!contract.isInstance(bean)) {
            throw new IllegalStateException("Bean named '" + qualifier + "' has type "
                    + bean.getClass().getName() + " but must implement " + contract.getName());
        }
        return bean;
    }
}

