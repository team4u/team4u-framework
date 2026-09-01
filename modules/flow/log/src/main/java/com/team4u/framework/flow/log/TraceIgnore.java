package com.team4u.framework.flow.log;

import java.lang.annotation.*;

/**
 * 流程上下文日志排除注解。
 *
 * <p>当类级别标注了 {@link TraceContext} 时，标注本注解的字段将被显式排除，不输出到单步与最终汇总日志中。
 * 适用于过滤超大报文、二进制字节流或内部临时缓存。</p>
 *
 * @author jay.wu
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface TraceIgnore {
}
