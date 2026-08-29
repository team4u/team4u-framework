package com.team4u.framework.singleflight.spring;

import com.team4u.framework.singleflight.proxy.SingleFlight;
import com.team4u.framework.singleflight.proxy.SingleFlightInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 可选的 Spring 集成开关：启用共享的 {@link SingleFlightInterceptor} 基础设施。
 * <p>
 * 开启后由 {@link SingleFlightSpringConfiguration} 注册 Bean 后置处理器，自动包装
 * 含 {@code @SingleFlight} 方法的 bean；也可以不使用本开关，在业务代码中通过
 * {@code SingleFlightProxyFactory.proxy(target)} 自行创建代理。
 * </p>
 *
 * @author jay.wu
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(SingleFlightSpringConfiguration.class)
public @interface EnableSingleFlight {
}
