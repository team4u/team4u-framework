package com.team4u.framework.retry.spring;

import com.team4u.framework.base.util.SpringUtil;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.retry.inline.DefaultInlineRetryClient;
import com.team4u.framework.retry.inline.InlineRetryClient;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;
import com.team4u.framework.retry.common.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.proxy.RetryDelegate;
import com.team4u.framework.retry.proxy.RetryInterceptor;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Spring AOP 体系下的重试拦截器实现。
 * <p>
 * 本类是 aopalliance {@link MethodInterceptor} 到 retry-proxy 核心
 * {@link RetryInterceptor} 的<b>适配壳</b>：将 Spring 的 {@code MethodInvocation}
 * 适配为 proxy 模块的 {@link MethodInvocation} 后委托核心拦截器执行，
 * 重试控制逻辑本体只在核心拦截器中维护一份。
 * <p>
 * Spring 特有的差异通过两个手段注入：
 * <ul>
 *     <li>{@link #resolveTargetClass(MethodInvocation)} 覆盖为
 *     {@link AopUtils#getTargetClass(Object)}，正确处理嵌套代理；</li>
 *     <li>{@link #resolveRecoveryTargetBeanName(MethodInvocation)} 覆盖为
 *     从容器解析目标 Bean 名称，供 MANAGED 模式回放时按名定位。</li>
 * </ul>
 * <p>
 * 目标 Bean 名称的解析结果按<b>代理实例</b>缓存（同一代理重复调用不重复遍历），
 * 且仅在 MANAGED 模式（核心拦截器按 {@code @Retryable#mode()} 判定）才会触发解析。
 */
public class SpringRetryInterceptor extends RetryInterceptor implements MethodInterceptor {

    private final BeanFactory beanFactory;
    private final ListableBeanFactory listableBeanFactory;
    private final RetryExecutorManager retryExecutorManager;

    /**
     * 代理实例 → 恢复目标 Bean 名称的缓存。
     * <p>
     * 键为调用现场传入的代理对象（getThis()），值可能为 null（按类型回放），
     * 用哨兵 {@link #NULL_BEAN_NAME} 占位以支持 ConcurrentHashMap 的 null 语义。
     */
    private final ConcurrentMap<Object, Object> recoveryBeanNameCache = new ConcurrentHashMap<Object, Object>();

    /**
     * 「解析过但无 Bean 名」的缓存占位（ConcurrentHashMap 不允许 null 值）
     */
    private static final Object NULL_BEAN_NAME = new Object();

    public SpringRetryInterceptor(
            BeanFactory beanFactory,
            ListableBeanFactory listableBeanFactory,
            RetryExecutorManager retryExecutorManager) {
        this.beanFactory = beanFactory;
        this.listableBeanFactory = listableBeanFactory;
        this.retryExecutorManager = retryExecutorManager;
    }

    @Override
    public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
        // 延迟初始化核心委托：Advisor 装配先于业务 Bean 就绪，需在首次拦截时才解析客户端
        ensureDelegateInitialized();
        return super.invoke(adapt(invocation));
    }

    /**
     * 确保重试委托类已初始化。
     * <p>
     * 采用双重检查锁定模式（DCL）实现延迟加载，自动从 Spring 容器获取重试客户端。
     */
    private void ensureDelegateInitialized() {
        if (getDelegate() == null) {
            synchronized (this) {
                if (getDelegate() == null) {
                    InlineRetryClient inlineClient = getBean(InlineRetryClient.class,
                            DefaultInlineRetryClient.getInstance());
                    ManagedRetryClient managedClient = getBean(ManagedRetryClient.class, null);
                    RetryDelegate delegate = new RetryDelegate(inlineClient, managedClient);
                    delegate.setScheduler(retryExecutorManager.getScheduler());
                    initializeDelegate(delegate);
                }
            }
        }
    }

    /**
     * 将 aopalliance 的方法调用上下文适配为 proxy 模块统一上下文
     */
    private MethodInvocation adapt(final org.aopalliance.intercept.MethodInvocation invocation) {
        return new MethodInvocation() {
            @Override
            public Object getProxy() {
                return invocation.getThis();
            }

            @Override
            public Object getTarget() {
                return invocation.getThis();
            }

            @Override
            public Method getMethod() {
                return invocation.getMethod();
            }

            @Override
            public Object[] getArguments() {
                return invocation.getArguments();
            }

            @Override
            public Object proceed() throws Throwable {
                return invocation.proceed();
            }
        };
    }

    /**
     * 从 BeanFactory 获取指定类型的 Bean。
     * <p>
     * 如果当前的 {@code beanFactory} 中不存在，则尝试通过 {@link SpringUtil} 静态查找。
     *
     * @param clazz        Bean 类型
     * @param defaultValue 默认值
     * @param <T>          泛型类型
     * @return 找到的 Bean 实例或默认值
     */
    private <T> T getBean(Class<T> clazz, T defaultValue) {
        try {
            return beanFactory.getBean(clazz);
        } catch (BeansException e) {
            try {
                return SpringUtil.getBean(clazz);
            } catch (Exception ex) {
                return defaultValue;
            }
        }
    }

    @Override
    protected Class<?> resolveTargetClass(MethodInvocation invocation) {
        Object target = invocation.getTarget();
        if (target == null) {
            return null;
        }
        // Spring 环境下可能存在嵌套代理，用 AopUtils 还原最终目标类型
        return AopUtils.getTargetClass(target);
    }

    @Override
    protected String resolveRecoveryTargetBeanName(MethodInvocation invocation) {
        final Object proxy = invocation.getTarget();
        if (proxy == null) {
            return null;
        }
        // 按代理实例缓存：Bean 名称在容器生命周期内不变，避免每次调用都遍历容器
        Object cached = recoveryBeanNameCache.computeIfAbsent(proxy, key -> {
            String name = doResolveBeanName(key);
            return name == null ? NULL_BEAN_NAME : name;
        });
        return NULL_BEAN_NAME.equals(cached) ? null : (String) cached;
    }

    /**
     * 实际执行目标 Bean 名称解析（每个代理实例至多一次）
     */
    private String doResolveBeanName(Object proxy) {
        // 遍历容器 Bean 定义，优先比对实例引用，命中即得到代理背后的注册名
        for (String beanName : listableBeanFactory.getBeanDefinitionNames()) {
            Object candidate;
            try {
                candidate = listableBeanFactory.getBean(beanName);
            } catch (BeansException ex) {
                continue;
            }
            if (candidate == proxy) {
                return beanName;
            }
        }
        // 按恢复目标类型兜底：容器内该类型唯一时可直接定位
        Class<?> targetType = AopUtils.getTargetClass(proxy);
        if (targetType == null) {
            return null;
        }
        Map<String, ?> candidates = listableBeanFactory.getBeansOfType(targetType);
        if (candidates.size() == 1) {
            return candidates.keySet().iterator().next();
        }
        return null;
    }
}
