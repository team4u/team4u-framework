package com.team4u.framework.bean.provider;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.bean.core.BeanFactory;
import com.team4u.framework.bean.core.BeanRegistry;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

/**
 * Spring 环境适配器
 * <p>
 * 只有在 Spring 容器扫描并初始化此类时才会激活桥接。
 *
 * @author jay.wu
 */
public class SpringBeanContainer implements BeanFactory, BeanRegistry, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        // 注册到全局管理器
        BeanManager.getInstance().addProvider(this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getBean(String name) {
        if (!isContextActive()) {
            return null;
        }

        if (applicationContext.containsBean(name)) {
            return (T) applicationContext.getBean(name);
        }
        return null;
    }

    @Override
    public <T> T getBean(Class<T> type) {
        if (!isContextActive()) {
            return null;
        }

        try {
            return applicationContext.getBean(type);
        } catch (NoSuchBeanDefinitionException e) {
            return null;
        }
    }

    @Override
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        if (!isContextActive()) {
            return java.util.Collections.emptyMap();
        }

        return applicationContext.getBeansOfType(type);
    }

    @Override
    public <T> boolean registerBean(String beanName, T bean) {
        if (!isContextActive()) {
            return false;
        }

        if (applicationContext instanceof ConfigurableApplicationContext) {
            ConfigurableListableBeanFactory beanFactory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
            if (!beanFactory.containsSingleton(beanName)) {
                beanFactory.registerSingleton(beanName, bean);
                return true;
            }
        }
        return false;
    }

    private boolean isContextActive() {
        if (applicationContext == null) {
            return false;
        }

        if (applicationContext instanceof ConfigurableApplicationContext) {
            return ((ConfigurableApplicationContext) applicationContext).isActive();
        }

        return true;
    }

    @Override
    public <T> boolean registerBean(T bean) {
        return registerBean(bean.getClass().getName(), bean);
    }

    @Override
    public int getOrder() {
        // 优先级高于 Local
        return 100;
    }
}
