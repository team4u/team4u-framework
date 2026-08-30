package com.team4u.framework.retry.proxy;

import com.team4u.framework.retry.managed.recovery.RecoveryHandler;

import java.lang.annotation.*;

/**
 * 重试机制标识注解
 * <p>
 * 该注解用于标识方法或类支持自动重试增强。当应用于类时，类中所有公共方法都将具备重试能力。
 * 结合 team4u-proxy 或 Spring AOP 使用，可实现对业务逻辑的透明化重试。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface Retryable {

    /**
     * 重试策略标识名称
     * <p>
     * 对应重试策略注册表中的 Key，默认为 "default"。可通过配置中心动态调整策略参数。
     */
    String policy() default "default";

    /**
     * 重试执行模式。
     */
    RetryMode mode() default RetryMode.INLINE;

    /**
     * 在 MANAGED 模式下，指定的恢复处理器类。
     * 代理 / Spring AOP 接入下仅支持默认值或显式指定 {@code InvocationReplay}。
     * 若需要自定义 taskType / payload，请改用 {@code ManagedRetryClient.submit(...)}。
     */
    Class<? extends RecoveryHandler> recovery() default RecoveryHandler.class;
}
