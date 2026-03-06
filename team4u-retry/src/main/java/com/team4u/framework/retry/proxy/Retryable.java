package com.team4u.framework.retry.proxy;

import com.team4u.framework.retry.RetryDurability;

import java.lang.annotation.*;

/**
 * 标识方法或类支持重试
 * <p>
 * 结合 team4u-proxy 使用，可实现业务逻辑的自动重试。
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
     * 系统发生宕机后，Worker 根据此类型查找对应的处理器进行恢复。
     * 若未指定，则默认使用方法名。
     */
    String taskType() default "";

    /**
     * 重试可靠性级别
     * <p>
     * 注意：设置为 {@link RetryDurability#AT_LEAST_ONCE_DURABLE} 时，框架会对方法入参进行持久化。
     * 对于高频或参数载荷巨大的接口，这会带来额外的 CPU 和内存开销。建议仅在参数精简且业务极其关键的场景下开启。
     */
    RetryDurability durability() default RetryDurability.MEMORY_ONLY;
}
