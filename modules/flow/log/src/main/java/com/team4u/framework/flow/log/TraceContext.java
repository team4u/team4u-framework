package com.team4u.framework.flow.log;

import java.lang.annotation.*;

/**
 * 流程上下文日志追踪注解。
 *
 * <p>支持两种声明粒度：
 * <ul>
 *   <li><b>标注在类（Class）上</b>：声明该类的所有字段默认全部输出至流程日志（全字段模式）；可通过在特定字段标注 {@link TraceIgnore} 排除个别大字段；</li>
 *   <li><b>标注在字段（Field）上</b>：作为白名单字段输出，并支持通过 {@link #value()} 设置在日志中的输出别名。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
@Target({ElementType.TYPE, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface TraceContext {

    /**
     * 字段输出别名（仅标注在字段上时生效；未指定时默认使用 Java 字段名）。
     *
     * @return 属性别名
     */
    String value() default "";
}
