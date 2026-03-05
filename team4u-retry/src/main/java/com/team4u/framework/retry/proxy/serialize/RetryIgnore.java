package com.team4u.framework.retry.proxy.serialize;

import java.lang.annotation.*;

/**
 * 标记重试时忽略序列化的参数
 * <p>
 * 被此注解标记的参数在构建重试快照时将被跳过（序列化结果为 null），
 * 适用于 HttpServletRequest、InputStream 或超大对象。
 *
 * @author antigravity
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RetryIgnore {
}
