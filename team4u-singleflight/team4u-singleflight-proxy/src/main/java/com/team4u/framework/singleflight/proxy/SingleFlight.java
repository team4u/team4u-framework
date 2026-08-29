package com.team4u.framework.singleflight.proxy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法注解：声明该方法进入回源合并协调。
 * <p>
 * 注解只声明 point（对应配置键 {@code team4u.singleflight.{point}}），
 * key、缓存与竞争策略全部由规则配置决定。代理会携带方法的泛型返回类型与
 * 参数名上下文执行——编译必须保留参数名（{@code -parameters}，框架父 POM 默认开启），
 * 参数名不可读的方法在代理创建期即失败，而不是静默退化为空上下文。
 * </p>
 *
 * @author jay.wu
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SingleFlight {

    /**
     * 规则切入点，配置键为 {@code team4u.singleflight.{point}}。
     */
    String value();
}
