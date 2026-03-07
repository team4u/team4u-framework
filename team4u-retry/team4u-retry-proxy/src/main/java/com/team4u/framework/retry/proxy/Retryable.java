package com.team4u.framework.retry.proxy;

import java.lang.annotation.*;

/**
 * 标识方法或类支持自动重试
 * <p>
 * 结合 team4u-proxy 或 Spring AOP 使用，可实现对业务方法的透明重试增强。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface Retryable {

    /**
     * 重试策略标识 Key
     */
    String policy() default "default";

    /**
     * 业务任务类型
     * <p>
     * 持久化模式下，系统发生宕机后，Worker 根据此类型查找对应的处理器进行恢复。
     * 若未指定，则由运行时模式自动推导默认值。
     */
    String taskType() default "";
}
