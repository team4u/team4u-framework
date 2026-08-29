package com.team4u.framework.singleflight.proxy;

import com.team4u.framework.proxy.ProxyBuilder;

/**
 * Convenience factory for singleflight proxies.
 *
 * @author jay.wu
 */
public final class SingleFlightProxyFactory {

    private SingleFlightProxyFactory() {
    }

    public static <T> T proxy(T target) {
        return ProxyBuilder.proxy(target, new SingleFlightInterceptor());
    }

    public static <T> T proxy(T target, SingleFlightExceptionHandler exceptionHandler) {
        return ProxyBuilder.proxy(target, new SingleFlightInterceptor(exceptionHandler));
    }

    public static <T> T proxy(Object target, Class<T> targetInterface) {
        return ProxyBuilder.forClass(targetInterface)
                .delegate(target)
                .intercept(new SingleFlightInterceptor())
                .build();
    }

    public static <T> T proxy(Object target, Class<T> targetInterface,
                              SingleFlightExceptionHandler exceptionHandler) {
        return ProxyBuilder.forClass(targetInterface)
                .delegate(target)
                .intercept(new SingleFlightInterceptor(exceptionHandler))
                .build();
    }
}
