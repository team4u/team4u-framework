package com.team4u.framework.retry.proxy;

import com.team4u.framework.retry.RetryDurability;

import java.lang.annotation.*;

/**
 * 标记方法支持重试
 * <p>
 * 结合 team4u-proxy 使用，无缝切入自动重试。
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface Retryable {

    /**
     * @return 重试策略对应的标识 Key
     */
    String value() default "default";

    /**
     * @return 重试可靠性级别
     * <p>
     * 注意：若设置为 {@link RetryDurability#STRONG_CONSISTENCY}，框架会对入参进行序列化持久化。
     * 对于高频且参数载荷巨大的接口，这可能导致显著的 CPU 和内存开销。建议仅在参数精简且业务极其关键的场景下开启。
     */
    RetryDurability durability() default RetryDurability.MEMORY_ONLY;
}
