package com.team4u.framework.retry.proxy.serialize;

import java.lang.annotation.*;

/**
 * 重试参数忽略注解
 * <p>
 * 用于标记在构建重试任务快照时应忽略的方法参数。
 * 被此注解标记的参数在持久化序列化过程中将被跳过，
 * 适用于 HttpServletRequest、InputStream 或超大对象等无法或无需持久化的场景。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RetryIgnore {
}
