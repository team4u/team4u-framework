package com.team4u.log.proxy;

import java.lang.annotation.*;

/**
 * 自动日志追踪注解
 * <p>
 * 用于标记需要自动记录入参、出参、耗时及异常的方法或类。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AutoLogTrace {

    /**
     * 业务动作标识（可选）。
     * <p>
     * 若不指定，则默认使用当前调用的方法名称。
     */
    String action() default "";

    /**
     * 慢日志阈值（毫秒）。超过此值时，成功日志级别提升为 WARN
     */
    long slowThreshold() default -1;

    /**
     * 忽略的异常列表。命中时日志级别降为 WARN（减少干扰）
     */
    Class<? extends Throwable>[] ignoreExceptions() default {};
}
