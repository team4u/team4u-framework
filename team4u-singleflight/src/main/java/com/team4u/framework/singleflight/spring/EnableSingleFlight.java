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
 * Optional Spring integration: enables the shared {@link SingleFlightInterceptor}
 * infrastructure. Method proxies are created with
 * {@code SingleFlightProxyFactory.proxy(target)} from user code or bean
 * post-processing built on that factory.
 *
 * @author jay.wu
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(SingleFlightSpringConfiguration.class)
public @interface EnableSingleFlight {
}
