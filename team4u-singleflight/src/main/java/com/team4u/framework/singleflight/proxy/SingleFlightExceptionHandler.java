package com.team4u.framework.singleflight.proxy;

import java.lang.reflect.Method;

/**
 * Converts component exceptions at the proxy boundary into method-compatible
 * business return values.
 *
 * @author jay.wu
 */
public interface SingleFlightExceptionHandler {

    /**
     * @param method             intercepted method
     * @param genericReturnType  {@link Method#getGenericReturnType()}
     * @param throwable          component exception (never a loader exception)
     * @param arguments          parameter-name to argument-value map
     * @return value assignable to genericReturnType
     */
    Object handle(Method method, java.lang.reflect.Type genericReturnType,
                  Throwable throwable, java.util.Map<String, Object> arguments) throws Exception;
}
