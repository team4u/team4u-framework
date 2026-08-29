package com.team4u.framework.retry.proxy;

import com.team4u.framework.base.util.Assert;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.proxy.support.AnnotatedMethodResolver;
import com.team4u.framework.retry.inline.InlineRetryClient;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;

import java.lang.reflect.Method;

/**
 * 基于 team4u-proxy 实现的自动重试拦截器
 * <p>
 * 该拦截器是重试代理拦截的<b>核心实现</b>：负责识别目标方法或类上的 {@link Retryable}
 * 注解，并委托给 {@link RetryDelegate} 执行具体的重试控制逻辑。注解解析统一委托
 * {@link AnnotatedMethodResolver}（按 (method, targetClass) 缓存），
 * 解析不到注解时直通业务逻辑。
 * <p>
 * 生态内其他拦截器形态（如 retry-spring 下的 aopalliance 适配壳）应将各自的
 * {@code MethodInvocation} 适配为本模块的 {@link MethodInvocation} 后复用本类，
 * 避免两套拦截逻辑漂移。宿主环境特有的差异通过覆盖
 * {@link #resolveTargetClass(MethodInvocation)} 与
 * {@link #resolveRecoveryTargetBeanName(MethodInvocation)} 两个钩子注入。
 */
public class RetryInterceptor implements MethodInterceptor {

    /**
     * {@link Retryable} 注解解析器（AnnotatedMethodResolver 自带 (method, targetClass) 级缓存）
     */
    private static final AnnotatedMethodResolver<Retryable> RESOLVER =
            AnnotatedMethodResolver.of(Retryable.class);

    private volatile RetryDelegate delegate;

    public RetryInterceptor(InlineRetryClient inlineClient, ManagedRetryClient managedClient) {
        Assert.notNull(inlineClient, "InlineRetryClient must not be null");
        this.delegate = new RetryDelegate(inlineClient, managedClient);
    }

    /**
     * 供需要延迟初始化重试客户端的适配壳复用核心逻辑。
     * <p>
     * 例如 Spring 环境下 Advisor 装配先于业务 Bean 就绪，需在首次拦截时才从容器
     * 解析 {@link InlineRetryClient}/{@link ManagedRetryClient}。子类必须在首次
     * {@link #invoke(MethodInvocation)} 前通过 {@link #initializeDelegate(RetryDelegate)}
     * 完成初始化。
     */
    protected RetryInterceptor() {
    }

    /**
     * 注入内部重试委托（延迟初始化场景，仅允许初始化一次语义上的赋值）
     *
     * @param delegate 已完成客户端装配的重试委托
     */
    protected final void initializeDelegate(RetryDelegate delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("RetryDelegate must not be null");
        }
        this.delegate = delegate;
    }

    /**
     * @return 内部持有的重试委托（延迟初始化完成前可能为 null）
     */
    public final RetryDelegate getDelegate() {
        return delegate;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method interfaceMethod = invocation.getMethod();
        Class<?> targetClass = resolveTargetClass(invocation);
        RetryMethodResolver.ResolvedRetryMethod resolved = RetryMethodResolver.resolve(interfaceMethod, targetClass);
        Retryable retryable = resolved.getRetryable();

        // 恢复目标 Bean 名称仅在 MANAGED 模式下才有意义（回放时按名定位 Bean），
        // 且解析成本较高，INLINE 模式一律跳过，避免无谓开销
        String recoveryTargetBeanName = null;
        if (retryable != null && retryable.mode() == RetryMode.MANAGED) {
            recoveryTargetBeanName = resolveRecoveryTargetBeanName(invocation);
        }

        return currentDelegate().executeWithRetry(
                interfaceMethod,
                resolved.getEffectiveMethod(),
                resolved.getRecoveryTargetType(),
                recoveryTargetBeanName,
                invocation.getArguments(),
                retryable,
                () -> {
                    try {
                        return invocation.proceed();
                    } catch (Exception | Error e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
    }

    /**
     * 解析注解查找所用的目标类型。
     * <p>
     * 默认取目标对象的具体类型；宿主环境存在代理嵌套时可覆盖为更精确的还原逻辑
     * （如 Spring 的 {@code AopUtils.getTargetClass}）。
     *
     * @param invocation 方法执行上下文
     * @return 目标类型；无目标对象时返回 null
     */
    protected Class<?> resolveTargetClass(MethodInvocation invocation) {
        return invocation.getTarget() == null ? null : invocation.getTarget().getClass();
    }

    /**
     * 解析 MANAGED 模式恢复时的目标 Bean 名称。
     * <p>
     * 默认返回 null（按类型定位）；宿主环境存在同名类型多 Bean 场景时可覆盖
     * 为按容器注册名解析。本方法仅在 {@link Retryable#mode()} 为
     * {@link RetryMode#MANAGED} 时才会被调用。
     *
     * @param invocation 方法执行上下文
     * @return 目标 Bean 名称；无法确定时返回 null
     */
    protected String resolveRecoveryTargetBeanName(MethodInvocation invocation) {
        return null;
    }

    private RetryDelegate currentDelegate() {
        RetryDelegate current = delegate;
        if (current == null) {
            throw new IllegalStateException(
                    "RetryDelegate has not been initialized before first invocation");
        }
        return current;
    }
}
