package com.team4u.framework.proxy.spring;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.support.AnnotatedMethodResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * 注解驱动代理装配的 Spring BeanPostProcessor 抽象模板
 * <p>
 * 收敛 ratelimiter/singleflight/retry/log/router 各模块同构的
 * 「扫描注解 Bean → 生成代理 → 替换注入」装配逻辑。子类只需提供三要素：
 * <ul>
 *     <li>{@link #getAnnotationType()}：目标注解类型（注解本身由业务模块定义）</li>
 *     <li>{@link #createInterceptor(Annotation)}：从注解实例构造拦截器的工厂</li>
 *     <li>（可选）{@link #buildProxy(Object, MethodInterceptor)}：覆盖默认代理构建方式
 *     （默认按目标具体类型走 {@link ProxyBuilder#proxy(Object, MethodInterceptor...)}，
 *     接口目标自动路由 JDK 引擎、普通类走 ByteBuddy 子类代理，与各模块现有工厂行为一致）</li>
 * </ul>
 * 防御性边界对齐 ratelimiter 现有实现：null Bean、Spring AOP 基础设施
 * （BeanPostProcessor / Advisor / {@link AopInfrastructureBean}）、已被 AOP 增强的代理、
 * {@link FactoryBean} 本体一律跳过；final 类等无法代理的目标默认 warn 并返回原 Bean
 * （不阻断启动），子类可覆盖 {@link #onProxyFailure(Object, String, Exception)} 改为快速失败。
 * <p>
 * 注解解析统一委托 {@link AnnotatedMethodResolver}（含桥接方法、接口方法与
 * targetClass 实现方法查找），因此注解可标注在实现方法、接口方法或类（需注解自身
 * 支持 TYPE 目标）上。createInterceptor 收到的是 Bean 上发现的第一个注解实例，
 * 用于构造 Bean 级拦截器配置；方法级差异应在拦截器内经
 * {@link #getResolver()} 按 (method, targetClass) 逐方法解析。
 *
 * @param <A> 目标注解类型
 * @author jay.wu
 */
public abstract class AnnotationProxyBeanPostProcessor<A extends Annotation> implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AnnotationProxyBeanPostProcessor.class);

    /**
     * 注解解析器（懒加载，按 (method, targetClass) 缓存解析结果）
     */
    private volatile AnnotatedMethodResolver<A> resolver;

    /**
     * @return 目标注解类型
     */
    protected abstract Class<A> getAnnotationType();

    /**
     * 从注解实例构造方法拦截器
     * <p>
     * 每个被代理的 Bean 调用一次，入参为该 Bean 上发现的第一个注解实例。
     * 返回的拦截器负责在调用期识别未注解方法并直通（{@code invocation.proceed()}）。
     *
     * @param annotation Bean 上发现的注解实例
     * @return 方法拦截器
     */
    protected abstract MethodInterceptor createInterceptor(A annotation);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean == null || isSkippable(bean)) {
            return bean;
        }
        A annotation = findAnnotation(bean.getClass());
        if (annotation == null) {
            return bean;
        }
        try {
            return buildProxy(bean, createInterceptor(annotation));
        } catch (Exception e) {
            return onProxyFailure(bean, beanName, e);
        }
    }

    /**
     * 是否为无需代理的边界 Bean：AOP 基础设施、既有 AOP 代理或 FactoryBean 本体
     * <p>
     * FactoryBean 本体被包装会破坏其产品语义（需要代理的是 getObject() 的产品），
     * 与既有 AOP 代理一样直接跳过，避免双层代理。
     *
     * @param bean 待检查的 Bean 实例
     * @return true 表示跳过代理装配
     */
    protected boolean isSkippable(Object bean) {
        return bean instanceof BeanPostProcessor
                || bean instanceof Advisor
                || bean instanceof AopInfrastructureBean
                || bean instanceof FactoryBean
                || AopUtils.isAopProxy(bean);
    }

    /**
     * 在 Bean 类的公有方法（含继承与接口方法）上解析第一个生效的注解实例
     *
     * @param beanClass Bean 的具体类型
     * @return 第一个解析到的注解实例；无则返回 null
     */
    protected A findAnnotation(Class<?> beanClass) {
        AnnotatedMethodResolver<A> methodResolver = getResolver();
        for (Method method : beanClass.getMethods()) {
            A annotation = methodResolver.resolve(method, beanClass);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * 构建代理对象。默认与各模块现有工厂行为一致：按目标具体类型构建，
     * 经 {@link ProxyBuilder} 智能路由引擎（接口 → JDK 引擎；普通类 → ByteBuddy 子类代理）。
     * <p>
     * 子类可覆盖以指定接口类型代理（{@code ProxyBuilder.forClass(iface).delegate(bean)...}）
     * 或追加额外拦截器。
     *
     * @param bean        原始 Bean
     * @param interceptor 拦截器
     * @return 代理对象
     */
    protected Object buildProxy(Object bean, MethodInterceptor interceptor) {
        return ProxyBuilder.proxy(bean, interceptor);
    }

    /**
     * 代理构建失败时的处置。默认对齐 ratelimiter 行为：记录 warn 并返回原 Bean，
     * 不阻断容器启动（final 类等无法代理的目标由此兜底）。
     * <p>
     * 需要快速失败的模块（如 singleflight 现状）可覆盖本方法直接抛出异常。
     *
     * @param bean     原始 Bean
     * @param beanName Bean 名称
     * @param e        构建失败异常
     * @return 替换注入的对象（默认返回原 Bean）
     */
    protected Object onProxyFailure(Object bean, String beanName, Exception e) {
        log.warn("{}|proxySkipped|bean={}|class={}|reason={}",
                getAnnotationType().getSimpleName(), beanName, bean.getClass().getName(), e.getMessage());
        return bean;
    }

    /**
     * @return 本处理器对应的注解方法解析器（子类拦截器内可复用做逐方法解析）
     */
    protected AnnotatedMethodResolver<A> getResolver() {
        AnnotatedMethodResolver<A> result = resolver;
        if (result == null) {
            synchronized (this) {
                result = resolver;
                if (result == null) {
                    result = new AnnotatedMethodResolver<A>(getAnnotationType());
                    resolver = result;
                }
            }
        }
        return result;
    }
}
