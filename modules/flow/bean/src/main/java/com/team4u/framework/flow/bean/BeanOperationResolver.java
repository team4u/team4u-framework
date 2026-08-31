package com.team4u.framework.flow.bean;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.bean.exception.NoSuchBeanDefinitionException;
import com.team4u.framework.flow.OperationResolver;

import java.util.Objects;

/**
 * Resolves flow extension-point bindings from a {@link BeanManager} without
 * replacing or unwrapping the object supplied by the container.
 */
public final class BeanOperationResolver implements OperationResolver {
    private final BeanManager beanManager;

    /** Uses the global {@link BeanManager}. */
    public BeanOperationResolver() {
        this(BeanManager.getInstance());
    }

    /**
     * Uses the supplied manager. This is primarily useful when container setup
     * already exposes the manager explicitly.
     */
    public BeanOperationResolver(BeanManager beanManager) {
        this.beanManager = Objects.requireNonNull(beanManager, "beanManager must not be null");
    }

    /** Returns a resolver backed by the global {@link BeanManager}. */
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
