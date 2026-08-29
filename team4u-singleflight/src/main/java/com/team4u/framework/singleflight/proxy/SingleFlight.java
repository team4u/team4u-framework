package com.team4u.framework.singleflight.proxy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method annotation for singleflight coordination.
 * <p>
 * Compilation must retain parameter names ({@code -parameters}); the framework
 * parent POM enables it by default. Methods without usable parameter names fail
 * at proxy creation instead of silently using an empty context.
 * </p>
 *
 * @author jay.wu
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SingleFlight {

    /**
     * Rule point; configuration key is {@code team4u.singleflight.{point}}.
     */
    String value();
}
