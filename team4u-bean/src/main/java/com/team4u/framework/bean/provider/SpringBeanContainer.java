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

import java.util.Collections;
import java.util.Map;

/**
 * Spring 环境适配器
 * <p>
 * 该类作为 {@link BeanFactory} 和 {@link BeanRegistry} 的实现，将 team4u 的 Bean 管理请求委托给 Spring 的 {@link ApplicationContext}。
 * 只有当此类作为 Spring Bean 被扫描并注入 {@link ApplicationContext} 时，桥接功能才会激活并自动注册到 {@link BeanManager}。
 *
 * @author jay.wu
 */
public class SpringBeanContainer implements BeanFactory, BeanRegistry, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        // 将当前容器实例注册到全局 BeanManager 门面中
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
            return Collections.emptyMap();
        }

        return applicationContext.getBeansOfType(type);
    }

    @Override
    public <T> boolean registerBean(String beanName, T bean) {
        if (!isContextActive()) {
            return false;
        }

        // 尝试向 Spring 运行时上下文动态注册单例 Bean
        if (applicationContext instanceof ConfigurableApplicationContext) {
            ConfigurableListableBeanFactory beanFactory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
            if (!beanFactory.containsSingleton(beanName)) {
                beanFactory.registerSingleton(beanName, bean);
                return true;
            }
        }
        return false;
    }

    /**
     * 检查 Spring 上下文是否已就绪且处于活跃状态
     */
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
