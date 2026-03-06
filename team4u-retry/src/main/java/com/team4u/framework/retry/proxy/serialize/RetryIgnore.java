package com.team4u.framework.retry.proxy.serialize;

import java.lang.annotation.*;

/**
 * 标记重试快照构建时应忽略的参数
 * <p>
 * 被此注解标记的参数在持久化重试快照时将被跳过，
 * 适用于 HttpServletRequest、InputStream 或超大对象等无法或无需序列化的场景。
 *
 * @author antigravity
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RetryIgnore {
}
